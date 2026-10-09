package org.alter.data.admin

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminServerTests {
    private val token = RunFile.newToken()
    private val server = AdminServer(
        port = 0,
        token = token,
        routes = listOf(
            AdminRoute("GET", "/health") { AdminResponse.ok(mapOf("status" to "up")) },
            AdminRoute("POST", "/shutdown") { request -> AdminResponse(202, mapOf("ticks" to request.query["ticks"])) },
        ),
    ).start()
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() = server.close()

    private fun call(method: String, path: String, auth: String? = "Bearer $token"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).method(method, HttpRequest.BodyPublishers.noBody())
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `requests without the token are refused`() {
        assertEquals(401, call("GET", "/health", auth = null).statusCode())
        assertEquals(401, call("GET", "/health", auth = "Bearer wrong").statusCode())
    }

    @Test
    fun `routes answer with json`() {
        val response = call("GET", "/health")
        assertEquals(200, response.statusCode())
        assertEquals("""{"status":"up"}""", response.body())
        assertEquals("""{"ticks":"5"}""", call("POST", "/shutdown?ticks=5").body())
        assertEquals(404, call("GET", "/nope").statusCode())
        assertEquals(404, call("GET", "/shutdown").statusCode())
    }

    @Test
    fun `event stream receives published events`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/events")).header("Authorization", "Bearer $token").build()
        val lines = http.send(request, HttpResponse.BodyHandlers.ofLines()).body().iterator()
        assertEquals(": connected", lines.next())
        repeat(50) { if (server.events.clientCount == 0) Thread.sleep(20) }
        server.events.publish("missing", mapOf("key" to "NPC_OP:3108:-1:1:-1"))
        val received = CompletableFuture.supplyAsync { listOf(lines.next(), lines.next(), lines.next(), lines.next()) }.get(5, TimeUnit.SECONDS)
        assertTrue("event: missing" in received, "$received")
        assertTrue(received.any { it == """data: {"key":"NPC_OP:3108:-1:1:-1"}""" }, "$received")
    }

    @Test
    fun `run file round-trips`() {
        val file = Files.createTempDirectory("run").resolve("server.json")
        val run = RunFile(pid = 1234, adminPort = 43595, adminToken = token, revision = 228, startedAt = "2026-10-09T00:00:00Z")
        run.write(file)
        assertEquals(run, RunFile.read(file))
    }
}
