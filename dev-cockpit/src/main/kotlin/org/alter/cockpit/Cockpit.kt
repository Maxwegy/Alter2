package org.alter.cockpit

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.alter.cockpit.auth.Role
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.inbox.EnrichExecutor
import org.alter.cockpit.inbox.ServerStartExecutor
import org.alter.cockpit.inbox.sources.MissingContentSource
import org.alter.cockpit.server.CockpitServer
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.alter.cockpit.store.TokenStore
import org.alter.cockpit.supervisor.AdminClient
import org.alter.cockpit.supervisor.GameServerSupervisor
import org.alter.cockpit.supervisor.LogTail
import org.alter.cockpit.wiki.PageResolver
import org.alter.cockpit.workorders.EnrichmentService
import org.alter.cockpit.workorders.PickpocketEnricher
import org.alter.cockpit.workorders.RecipeEnricher
import org.alter.cockpit.workorders.SceneryEnricher
import org.alter.cockpit.workorders.TalkToEnricher
import org.alter.cockpit.workorders.TradeEnricher
import org.alter.cockpit.workorders.WikiPages
import org.alter.cockpit.workorders.WikiUrls
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.io.AtomicFiles
import org.alter.data.missing.MissingContentEvent
import org.alter.data.wiki.WikiBucketClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

/** Wiring only: builds every part with its dependencies and runs the background loops. */
class Cockpit private constructor(
    private val db: Database,
    private val scope: CoroutineScope,
    private val bus: EventBus,
    private val inbox: InboxService,
    private val supervisor: GameServerSupervisor,
    private val logTail: LogTail,
    private val missingSource: MissingContentSource,
    private val server: CockpitServer,
    private val wikiHttp: WikiHttpClient,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        scope.launch { logTail.run() }
        scope.launch { missingSource.run() }
        scope.launch {
            while (isActive) {
                delay(60_000)
                runCatching { inbox.wakeSnoozed() }.onFailure { logger.warn(it) { "Could not wake snoozed cards" } }
            }
        }
        scope.launch {
            bus.flow.collect { event ->
                if (event.type == "server.missing") {
                    runCatching { missingSource.onLiveEvent(Json.mapper.convertValue(event.payload, MissingContentEvent::class.java)) }
                        .onFailure { logger.debug(it) { "Ignoring malformed missing event" } }
                }
            }
        }
        supervisor.startEventMirror()
        engine = server.start()
    }

    override fun close() {
        engine?.stop(1_000, 2_000)
        scope.cancel()
        wikiHttp.close()
        db.close()
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        fun build(paths: DataPaths, config: CockpitConfig, newOwnerToken: Boolean = false): Cockpit {
            val infra = InfraConfig.load(paths.config)
            val db = Database.open(paths.cockpitDir.resolve("cockpit.db"))
            val tokens = TokenStore(db)
            bootstrapOwnerToken(tokens, paths.cockpitDir.resolve("owner.token"), newOwnerToken)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val audit = AuditStore(db)
            val resolver = PageResolver(infra.wiki)
            val logTail = LogTail(paths.logFile, config.supervisor.logTailLines, bus)
            lateinit var inbox: InboxService
            val supervisor = GameServerSupervisor(config.supervisor, paths, AdminClient(), bus, scope) { code ->
                if (code != 0) {
                    inbox.propose(
                        kind = "server.start",
                        sourceKey = "server.exit:${Instant.now()}",
                        title = "The game server exited with code $code",
                        summary = "It was not a requested stop. The last log lines are in the evidence.",
                        evidence = mapOf("exitCode" to code, "log" to logTail.snapshot(30)),
                        plan = listOf(PlanStep(PlanStep.RUN, "Start the game server again")),
                    )
                }
            }
            val wikiHttp = WikiHttpClient(infra.wiki)
            val enrichment = EnrichmentService(
                resolver,
                WikiPages(wikiHttp, paths.cockpitDir.resolve("wiki-pages"), Duration.ofHours(infra.wiki.rawCacheTtlHours)),
                WikiBucketClient(wikiHttp),
                WikiUrls(),
                listOf(TalkToEnricher(), TradeEnricher(), PickpocketEnricher(), SceneryEnricher(), RecipeEnricher()),
            )
            inbox = InboxService(InboxStore(db), audit, bus, listOf(EnrichExecutor(enrichment), ServerStartExecutor(supervisor)), scope)
            val missingSource = MissingContentSource(paths.missingContent, inbox, enrichment::plan, config.inbox.minCountForCard, config.inbox.missingContentPollSeconds * 1_000)
            val server = CockpitServer(config, tokens, audit, inbox, bus, supervisor, logTail, paths.missingContent)
            return Cockpit(db, scope, bus, inbox, supervisor, logTail, missingSource, server, wikiHttp)
        }

        /**
         * The first run has no tokens, so an owner token is issued and its plaintext written to
         * `data/cockpit/owner.token` (owner-readable where the OS supports it). [force] reissues it.
         */
        private fun bootstrapOwnerToken(tokens: TokenStore, file: Path, force: Boolean) {
            if (tokens.count() > 0 && !force) return
            if (force) tokens.list().filter { it.role == Role.OWNER && it.label == "owner" }.forEach { tokens.revoke(it.id) }
            val (plaintext, _) = tokens.issue(Role.OWNER, "owner")
            AtomicFiles.writeText(file, plaintext + "\n")
            runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
            logger.info { "Owner token written to $file; paste it into the cockpit UI." }
        }
    }
}
