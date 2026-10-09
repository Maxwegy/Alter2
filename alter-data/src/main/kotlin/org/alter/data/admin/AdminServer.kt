package org.alter.data.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AdminRequest(val method: String, val path: String, val query: Map<String, String>)

data class AdminResponse(val status: Int, val body: Any) {
    companion object {
        fun ok(body: Any) = AdminResponse(200, body)
    }
}

/** A route: method + path to handler. Handlers run on the admin server's own threads, never the game thread. */
data class AdminRoute(val method: String, val path: String, val handler: (AdminRequest) -> AdminResponse)

/**
 * Pushes server-sent events to every connected `/events` client. Dead connections are dropped on the next
 * write. Thread-safe.
 */
class EventBroadcaster {
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val mapper = ObjectMapper().registerKotlinModule()

    val clientCount: Int get() = clients.size

    internal fun add(stream: OutputStream) {
        clients += stream
    }

    fun publish(type: String, payload: Any) = send("event: $type\ndata: ${mapper.writeValueAsString(payload)}\n\n")

    internal fun heartbeat() = send(": ping\n\n")

    private fun send(frame: String) {
        val bytes = frame.toByteArray()
        clients.forEach { stream ->
            try {
                synchronized(stream) {
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (e: IOException) {
                clients -= stream
                runCatching { stream.close() }
            }
        }
    }

    internal fun closeAll() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }
}

/**
 * The server's local control API, used by the `alter` launcher and the Dev Cockpit.
 *
 * Listens on the loopback interface only and requires `Authorization: Bearer <token>` on every request.
 * `GET /events` is a server-sent-event stream fed by [events].
 */
class AdminServer(
    port: Int,
    private val token: String,
    routes: List<AdminRoute>,
    val events: EventBroadcaster = EventBroadcaster(),
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val mapper = ObjectMapper().registerKotlinModule()
    private val executor = Executors.newFixedThreadPool(4) { Thread(it, "admin-http").apply { isDaemon = true } }
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "admin-events").apply { isDaemon = true } }
    private val routes = routes.associateBy { it.method to it.path }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)

    /** The bound port (useful when started with port 0). */
    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
    }

    fun start(): AdminServer {
        server.start()
        heartbeat.scheduleAtFixedRate(events::heartbeat, 15, 15, TimeUnit.SECONDS)
        logger.info { "Admin control API listening on 127.0.0.1:$port" }
        return this
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!authorized(exchange.requestHeaders.getFirst("Authorization"))) {
                return respond(exchange, AdminResponse(401, mapOf("error" to "missing or wrong bearer token")))
            }
            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
            if (method == "GET" && path == "/events") {
                return openEventStream(exchange)
            }
            val route = routes[method to path] ?: return respond(exchange, AdminResponse(404, mapOf("error" to "no route $method $path")))
            respond(exchange, route.handler(AdminRequest(method, path, query(exchange.requestURI.rawQuery))))
        } catch (e: Exception) {
            logger.error(e) { "Admin request failed: ${exchange.requestMethod} ${exchange.requestURI}" }
            runCatching { respond(exchange, AdminResponse(500, mapOf("error" to (e.message ?: e::class.simpleName)))) }
        }
    }

    private fun openEventStream(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val stream = exchange.responseBody
        stream.write(": connected\n\n".toByteArray())
        stream.flush()
        events.add(stream)
    }

    private fun respond(exchange: HttpExchange, response: AdminResponse) {
        val bytes = mapper.writeValueAsBytes(response.body)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Constant-time comparison so the token can't be guessed byte by byte from response timing. */
    private fun authorized(header: String?): Boolean {
        val presented = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }

    override fun close() {
        heartbeat.shutdownNow()
        events.closeAll()
        server.stop(0)
        executor.shutdownNow()
    }
}
