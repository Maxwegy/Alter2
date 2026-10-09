package org.alter.plugins.content.infrastructure.admin

import gg.rsmod.util.BuildInfo
import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.admin.AdminRequest
import org.alter.data.admin.AdminResponse
import org.alter.data.admin.AdminRoute
import org.alter.data.admin.AdminServer
import org.alter.data.admin.EventBroadcaster
import org.alter.data.admin.RunFile
import org.alter.data.config.InfraConfig
import org.alter.data.snapshot.SnapshotRepository
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.GameService
import org.alter.game.service.Service
import org.alter.plugins.content.infrastructure.DataReload
import org.alter.plugins.content.infrastructure.GameDataService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The server's local control API (see [AdminServer]): health, graceful shutdown/restart, wiki reload and a
 * live event stream. Every handler that reads or changes the world does so on the game thread.
 *
 * Writes `data/run/server.json` (pid, port, token) so `scripts/alter` and the Dev Cockpit can find it.
 */
class AdminControlService(
    private val config: InfraConfig.Admin,
    private val runFile: Path,
    private val snapshotDir: Path,
    val events: EventBroadcaster,
) : Service {
    private val logger = KotlinLogging.logger {}
    private val startedAt = Instant.now()
    private lateinit var world: World
    private lateinit var game: GameService
    private var server: AdminServer? = null

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        this.world = world
        this.game = world.getService(GameService::class.java) ?: return logger.warn { "No GameService; admin API disabled." }
        val token = RunFile.newToken()
        val admin = try {
            AdminServer(config.port, token, routes(), events).start()
        } catch (e: Exception) {
            return logger.error(e) { "Could not start the admin API on 127.0.0.1:${config.port}; scripts/alter and the cockpit can't control this server." }
        }
        this.server = admin
        RunFile(ProcessHandle.current().pid(), admin.port, token, BuildInfo.REVISION, startedAt.toString()).write(runFile)
        Runtime.getRuntime().addShutdownHook(
            Thread({
                runCatching { Files.deleteIfExists(runFile) }
                admin.close()
            }, "admin-shutdown"),
        )
    }

    private fun routes() = listOf(
        AdminRoute("GET", "/health", ::health),
        AdminRoute("POST", "/shutdown", ::shutdown),
        AdminRoute("POST", "/wiki/reload", ::reloadWiki),
    )

    /** Runs [block] on the game thread and waits for it; a timeout means the tick loop is stalled. */
    private fun <T> onGameThread(timeoutMs: Long = 2_000, block: () -> T): T {
        val future = CompletableFuture<T>()
        game.submitGameThreadJob {
            try {
                future.complete(block())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun health(request: AdminRequest): AdminResponse {
        val base = mapOf(
            "revision" to BuildInfo.REVISION,
            "pid" to ProcessHandle.current().pid(),
            "uptimeSeconds" to (Instant.now().epochSecond - startedAt.epochSecond),
            "eventClients" to events.clientCount,
        )
        return try {
            val live = onGameThread {
                mapOf(
                    "cycle" to world.currentCycle,
                    "players" to world.players.count(),
                    "npcs" to world.npcs.count(),
                    "rebootTimer" to world.rebootTimer,
                    "shutdownScheduled" to ServerLifecycle.isScheduled,
                    "snapshot" to (world.getService(GameDataService::class.java)?.repository?.counts() ?: emptyMap()),
                )
            }
            AdminResponse.ok(base + ("status" to "up") + live)
        } catch (e: TimeoutException) {
            AdminResponse(503, base + ("status" to "stalled"))
        }
    }

    /** `POST /shutdown?ticks=N&restart=true|false`: graceful stop (or restart) after a countdown of N ticks. */
    private fun shutdown(request: AdminRequest): AdminResponse {
        val ticks = request.query["ticks"]?.toIntOrNull()?.coerceIn(0, 6_000) ?: 0
        val restart = request.query["restart"]?.toBoolean() ?: false
        val accepted = onGameThread { ServerLifecycle.schedule(world, ticks, restart) }
        if (!accepted) return AdminResponse(409, mapOf("error" to "a shutdown is already scheduled"))
        logger.info { "Admin API: ${if (restart) "restart" else "shutdown"} in $ticks ticks." }
        events.publish("lifecycle", mapOf("action" to if (restart) "restart" else "shutdown", "ticks" to ticks))
        return AdminResponse(202, mapOf("action" to if (restart) "restart" else "shutdown", "ticks" to ticks))
    }

    /** `POST /wiki/reload`: re-read the committed snapshot from disk (no network) and swap it in. */
    private fun reloadWiki(request: AdminRequest): AdminResponse {
        val loaded = SnapshotRepository.load(snapshotDir) as? SnapshotRepository.LoadResult.Loaded
            ?: return AdminResponse(409, mapOf("error" to "no loadable snapshot in $snapshotDir"))
        val result = onGameThread(10_000) { DataReload.apply(world, loaded.repository) }
        return AdminResponse.ok(mapOf("npcDefs" to result.npcDefs, "snapshot" to loaded.repository.counts()))
    }
}
