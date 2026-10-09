package org.alter.cockpit

import org.alter.cockpit.auth.Principal
import org.alter.cockpit.auth.Role
import org.alter.cockpit.inbox.ActionStatus
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.alter.cockpit.store.Migrations
import org.alter.cockpit.store.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoreTests {

    @Test
    fun `migrations run once and reopening keeps data`() {
        val file = Files.createTempDirectory("cockpit").resolve("cockpit.db")
        Database.open(file).use { TokenStore(it).issue(Role.VIEWER, "first") }
        Database.open(file).use { db ->
            assertEquals(1, TokenStore(db).count())
            val versions = db.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT version FROM schema_version").use { r -> generateSequence { if (r.next()) r.getInt(1) else null }.toList() } } }
            assertEquals(listOf(Migrations.version), versions)
        }
    }

    @Test
    fun `tokens authenticate by plaintext only and can be revoked`() {
        Database.inMemory().use { db ->
            val tokens = TokenStore(db)
            val (plaintext, record) = tokens.issue(Role.DEV, "laptop")
            assertEquals(Principal(record.id, Role.DEV, "laptop"), tokens.authenticate(plaintext))
            assertNull(tokens.authenticate(plaintext.dropLast(1)))
            assertNotNull(tokens.list().single().lastUsedAt)
            assertTrue(tokens.revoke(record.id))
            assertNull(tokens.authenticate(plaintext))
            assertFalse(tokens.revoke(record.id))
        }
    }

    @Test
    fun `audit entries come back newest first with their details`() {
        Database.inMemory().use { db ->
            val audit = AuditStore(db)
            val owner = Principal(1, Role.OWNER, "owner")
            audit.record(owner, "inbox.go", "card-1", mapOf("kind" to "enrich.npc_op"))
            audit.record(owner, "inbox.delete", "card-2", mapOf("reason" to "duplicate"))
            val entries = audit.list()
            assertEquals(listOf("inbox.delete", "inbox.go"), entries.map { it.action })
            assertEquals("duplicate", entries.first().details["reason"])
            assertEquals("OWNER", entries.first().role)
            assertEquals(1, audit.list(before = entries.first().id).size)
        }
    }

    @Test
    fun `inbox rows round-trip and list open cards first`() {
        Database.inMemory().use { db ->
            val store = InboxStore(db)
            val card = InboxAction(
                id = "a", kind = "enrich.npc_op", sourceKey = "missing:NPC_OP:1", title = "t", summary = "s",
                evidence = mapOf("count" to 3, "locations" to listOf(mapOf("x" to 1, "z" to 2))),
                plan = listOf(PlanStep(PlanStep.REQUEST, "lookup", "https://example")),
                params = mapOf("id" to 1), createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            )
            store.insert(card)
            store.insert(card.copy(id = "b", sourceKey = "missing:NPC_OP:2", status = ActionStatus.DONE, updatedAt = "2026-01-02T00:00:00Z"))
            store.insert(card.copy(id = "c", sourceKey = "missing:NPC_OP:3", status = ActionStatus.SNOOZED, updatedAt = "2026-01-03T00:00:00Z"))

            assertEquals(card, store.get("a"))
            assertEquals(card, store.getBySourceKey("missing:NPC_OP:1"))
            assertEquals(listOf("a", "c", "b"), store.list().map { it.id })
            assertEquals(listOf("b"), store.list(ActionStatus.DONE).map { it.id })
            assertEquals(mapOf(ActionStatus.PENDING to 1, ActionStatus.DONE to 1, ActionStatus.SNOOZED to 1), store.counts())

            store.update(card.copy(status = ActionStatus.FAILED, error = "boom", result = mapOf("x" to 1)))
            assertEquals("boom", store.get("a")?.error)
            assertEquals(mapOf("x" to 1), store.get("a")?.result)
        }
    }
}
