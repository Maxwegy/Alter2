package org.alter.cockpit

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.alter.cockpit.auth.Role
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.ActionExecutor
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.server.CockpitServer
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.alter.cockpit.store.TokenStore
import org.alter.cockpit.supervisor.AdminClient
import org.alter.cockpit.supervisor.GameServerSupervisor
import org.alter.cockpit.supervisor.LogTail
import org.alter.data.config.DataPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ApiTests {
    private val db = Database.inMemory()
    private val tokens = TokenStore(db)
    private val audit = AuditStore(db)
    private val bus = EventBus()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val executor = object : ActionExecutor {
        override fun handles(kind: String) = true
        override suspend fun execute(action: InboxAction) = mapOf("ok" to true)
    }
    private val inbox = InboxService(InboxStore(db), audit, bus, listOf(executor), scope)
    private val dataDir = Files.createTempDirectory("data")
    private val paths = DataPaths(dataDir)
    private val config = CockpitConfig()
    private val supervisor = GameServerSupervisor(config.supervisor, paths, AdminClient(), bus, scope)
    private val server = CockpitServer(config, tokens, audit, inbox, bus, supervisor, LogTail(paths.logFile, 10, bus), paths.missingContent)

    private val owner = tokens.issue(Role.OWNER, "owner").first
    private val viewer = tokens.issue(Role.VIEWER, "viewer").first

    private suspend fun HttpResponse.json(): Map<String, Any?> = Json.mapper.readValue(bodyAsText())

    @Test
    fun `health is open, everything else needs a valid token`() = testApplication {
        application { with(server) { module() } }
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals("up", client.get("/api/health").json()["cockpit"])
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/inbox").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/inbox") { header("Authorization", "Bearer wrong") }.status)
        val me = client.get("/api/me") { header("Authorization", "Bearer $viewer") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("VIEWER", me.json()["role"])
    }

    @Test
    fun `viewers read, devs decide, owners manage tokens`() = testApplication {
        application { with(server) { module() } }
        val card = inbox.propose("enrich.npc_op", "missing:1", "Talk-to Man", "once")!!

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/inbox/${card.id}/go") { header("Authorization", "Bearer $viewer") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/tokens") { header("Authorization", "Bearer $viewer") }.status)

        val issued = client.post("/api/tokens") {
            header("Authorization", "Bearer $owner")
            header("Content-Type", "application/json")
            setBody("""{"role":"dev","label":"laptop"}""")
        }
        assertEquals(HttpStatusCode.Created, issued.status)
        val dev = issued.json()["token"] as String

        val go = client.post("/api/inbox/${card.id}/go") {
            header("Authorization", "Bearer $dev")
            header("Content-Type", "application/json")
            setBody("""{"params":{"id":7}}""")
        }
        assertEquals(HttpStatusCode.OK, go.status)
        assertEquals("EDIT", go.json()["decision"])
        assertEquals("DONE", client.get("/api/inbox/${card.id}") { header("Authorization", "Bearer $dev") }.json()["status"])

        val deleteNoReason = client.post("/api/inbox/${card.id}/delete") { header("Authorization", "Bearer $dev"); setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, deleteNoReason.status)

        val audit = client.get("/api/audit") { header("Authorization", "Bearer $viewer") }.bodyAsText()
        assertTrue(audit.contains("\"action\":\"inbox.go\"") && audit.contains("\"action\":\"token.issue\""))

        val tokenId = (issued.json()["record"] as Map<*, *>)["id"]
        assertEquals(HttpStatusCode.OK, client.delete("/api/tokens/$tokenId") { header("Authorization", "Bearer $owner") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header("Authorization", "Bearer $dev") }.status)
    }

    @Test
    fun `server status reports stopped when nothing runs`() = testApplication {
        application { with(server) { module() } }
        val status = client.get("/api/server") { header("Authorization", "Bearer $viewer") }
        assertEquals(HttpStatusCode.OK, status.status)
        assertEquals("STOPPED", status.json()["state"])
        assertEquals(false, status.json()["managed"])
        assertEquals(HttpStatusCode.NotFound, client.get("/api/inbox/nope") { header("Authorization", "Bearer $viewer") }.status)
    }
}
