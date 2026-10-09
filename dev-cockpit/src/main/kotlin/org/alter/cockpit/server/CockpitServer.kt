package org.alter.cockpit.server

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import gg.rsmod.util.BuildInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alter.cockpit.CockpitConfig
import org.alter.cockpit.Json
import org.alter.cockpit.auth.Role
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.ActionStatus
import org.alter.cockpit.inbox.ApiException
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Migrations
import org.alter.cockpit.store.TokenStore
import org.alter.cockpit.supervisor.GameServerSupervisor
import org.alter.cockpit.supervisor.LogTail
import org.alter.cockpit.workorders.WorkOrders
import org.alter.data.missing.MissingContentFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The HTTP API and the static UI. Everything the routes touch is injected; nothing here owns state. */
class CockpitServer(
    private val config: CockpitConfig,
    private val tokens: TokenStore,
    private val audit: AuditStore,
    private val inbox: InboxService,
    private val bus: EventBus,
    private val supervisor: GameServerSupervisor,
    private val logTail: LogTail,
    private val missingContentFile: Path,
    private val workOrders: WorkOrders? = null,
) {
    private val logger = KotlinLogging.logger {}

    fun start(): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, port = config.port, host = config.bindAddress) { module() }
        logger.info { "Dev Cockpit listening on http://${config.bindAddress}:${config.port}" }
        return server.start(wait = false)
    }

    fun Application.module() {
        install(ContentNegotiation) {
            jackson {
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        install(SSE)
        install(StatusPages) {
            exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.status), mapOf("error" to cause.message)) }
            exception<IllegalArgumentException> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message)) }
            exception<IllegalStateException> { call, cause -> call.respond(HttpStatusCode.Conflict, mapOf("error" to cause.message)) }
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Request failed: ${call.request.local.method.value} ${call.request.local.uri}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: cause::class.simpleName)))
            }
        }
        installTokenAuth(tokens)

        routing {
            route("/api") {
                get("/health") {
                    call.respond(mapOf("cockpit" to "up", "revision" to BuildInfo.REVISION, "schemaVersion" to Migrations.version, "inbox" to inbox.counts()))
                }
                get("/me") { call.respond(call.principal) }

                route("/server") {
                    get { call.respond(withContext(Dispatchers.IO) { supervisor.status() }) }
                    get("/log") {
                        val lines = call.request.queryParameters["lines"]?.toIntOrNull() ?: 200
                        call.respond(mapOf("lines" to logTail.snapshot(lines)))
                    }
                    post("/start") {
                        call.require(Role.DEV)
                        val started = withContext(Dispatchers.IO) { supervisor.start() }
                        audit.record(call.principal, "server.start", details = mapOf("started" to started))
                        call.respond(mapOf("started" to started))
                    }
                    post("/stop") {
                        call.require(Role.DEV)
                        val ticks = call.ticks()
                        val stopped = withContext(Dispatchers.IO) { supervisor.stop(ticks) }
                        audit.record(call.principal, "server.stop", details = mapOf("ticks" to ticks, "running" to stopped))
                        call.respond(mapOf("stopping" to stopped, "ticks" to ticks))
                    }
                    post("/restart") {
                        call.require(Role.DEV)
                        val ticks = call.ticks()
                        val stopped = withContext(Dispatchers.IO) { supervisor.stop(ticks, restart = true) }
                        audit.record(call.principal, "server.restart", details = mapOf("ticks" to ticks, "running" to stopped))
                        call.respond(mapOf("restarting" to stopped, "ticks" to ticks))
                    }
                    post("/wiki-reload") {
                        call.require(Role.DEV)
                        val result = withContext(Dispatchers.IO) { supervisor.reloadWiki() }
                        audit.record(call.principal, "server.wiki-reload", details = result)
                        call.respond(result)
                    }
                }

                route("/inbox") {
                    get {
                        val status = call.request.queryParameters["status"]?.let { ActionStatus.valueOf(it.uppercase()) }
                        call.respond(inbox.list(status))
                    }
                    get("/counts") { call.respond(inbox.counts()) }
                    get("/{id}") { call.respond(inbox.get(call.id())) }
                    post("/{id}/go") {
                        call.require(Role.DEV)
                        val body = call.jsonBody()
                        @Suppress("UNCHECKED_CAST")
                        call.respond(inbox.go(call.id(), call.principal, body["params"] as? Map<String, Any?>))
                    }
                    post("/{id}/edit") {
                        call.require(Role.DEV)
                        @Suppress("UNCHECKED_CAST")
                        val params = call.jsonBody()["params"] as? Map<String, Any?> ?: throw ApiException(400, "Body needs a params object")
                        call.respond(inbox.edit(call.id(), call.principal, params))
                    }
                    post("/{id}/delete") {
                        call.require(Role.DEV)
                        val reason = call.jsonBody()["reason"] as? String ?: throw ApiException(400, "Body needs a reason")
                        call.respond(inbox.delete(call.id(), call.principal, reason))
                    }
                    post("/{id}/scaffold") {
                        call.require(Role.DEV)
                        val orders = workOrders ?: throw ApiException(503, "Work orders are not configured")
                        call.respond(withContext(Dispatchers.IO) { orders.scaffold(call.id(), call.principal) })
                    }
                    post("/{id}/apply") {
                        call.require(Role.DEV)
                        val orders = workOrders ?: throw ApiException(503, "Work orders are not configured")
                        call.respond(withContext(Dispatchers.IO) { orders.apply(call.id(), call.principal) })
                    }
                    post("/{id}/discard") {
                        call.require(Role.DEV)
                        val orders = workOrders ?: throw ApiException(503, "Work orders are not configured")
                        call.respond(withContext(Dispatchers.IO) { orders.discard(call.id(), call.principal) })
                    }
                    post("/{id}/snooze") {
                        call.require(Role.DEV)
                        val body = call.jsonBody()
                        val until = (body["until"] as? String)?.let(Instant::parse)
                            ?: (body["hours"] as? Number)?.let { Instant.now().plusSeconds(it.toLong() * 3_600) }
                            ?: throw ApiException(400, "Body needs until (ISO instant) or hours")
                        call.respond(inbox.snooze(call.id(), call.principal, until))
                    }
                }

                get("/audit") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1_000) ?: 100
                    call.respond(audit.list(limit, call.request.queryParameters["before"]?.toLongOrNull()))
                }

                get("/missing") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    val entries = if (Files.exists(missingContentFile)) Json.mapper.readValue<MissingContentFile>(missingContentFile.toFile()).entries else emptyList()
                    call.respond(mapOf("total" to entries.size, "entries" to entries.take(limit)))
                }

                route("/tokens") {
                    get {
                        call.require(Role.OWNER)
                        call.respond(tokens.list())
                    }
                    post {
                        call.require(Role.OWNER)
                        val body = call.jsonBody()
                        val role = (body["role"] as? String)?.let { Role.valueOf(it.uppercase()) } ?: throw ApiException(400, "Body needs a role")
                        val label = (body["label"] as? String)?.takeIf { it.isNotBlank() } ?: throw ApiException(400, "Body needs a label")
                        val (plaintext, token) = tokens.issue(role, label)
                        audit.record(call.principal, "token.issue", token.id.toString(), mapOf("role" to role.name, "label" to label))
                        call.respond(HttpStatusCode.Created, mapOf("token" to plaintext, "record" to token))
                    }
                    delete("/{id}") {
                        call.require(Role.OWNER)
                        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(400, "Bad token id")
                        if (id == call.principal.tokenId) throw ApiException(409, "You can't revoke the token you are using")
                        val revoked = tokens.revoke(id)
                        audit.record(call.principal, "token.revoke", id.toString(), mapOf("revoked" to revoked))
                        call.respond(mapOf("revoked" to revoked))
                    }
                }

                sse("/events") {
                    val heartbeat = launch {
                        while (isActive) {
                            delay(15_000)
                            send(ServerSentEvent(comments = "ping"))
                        }
                    }
                    try {
                        bus.flow.collect { event -> send(ServerSentEvent(data = Json.mapper.writeValueAsString(event.payload), event = event.type, id = event.at)) }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }

            staticResources("/", "static") { default("index.html") }
        }
    }

    private fun ApplicationCall.id(): String = parameters["id"] ?: throw ApiException(400, "Missing id")

    private fun ApplicationCall.ticks(): Int = request.queryParameters["ticks"]?.toIntOrNull()?.coerceIn(0, 6_000) ?: 0

    private suspend fun ApplicationCall.jsonBody(): Map<String, Any?> {
        val text = receiveText()
        return if (text.isBlank()) emptyMap() else Json.mapper.readValue(text)
    }
}
