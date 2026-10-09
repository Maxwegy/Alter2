package org.alter.cockpit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.alter.cockpit.auth.Principal
import org.alter.cockpit.auth.Role
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.ActionExecutor
import org.alter.cockpit.inbox.ActionStatus
import org.alter.cockpit.inbox.ApiException
import org.alter.cockpit.inbox.Decision
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InboxServiceTests {
    private val db = Database.inMemory()
    private val bus = EventBus()
    private val audit = AuditStore(db)
    private val dev = Principal(1, Role.DEV, "dev")
    private var clock: Clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC)
    private val executed = mutableListOf<InboxAction>()

    private val executor = object : ActionExecutor {
        override fun handles(kind: String) = kind.startsWith("enrich.")
        override suspend fun execute(action: InboxAction): Map<String, Any?> {
            executed += action
            if (action.params["fail"] == true) throw IllegalStateException("nope")
            return mapOf("pageUrl" to "https://w/Man")
        }
    }

    // Unconfined: executors that never suspend finish before go() returns, so tests need no waiting.
    private val service = InboxService(InboxStore(db), audit, bus, listOf(executor), CoroutineScope(Dispatchers.Unconfined), clock)

    private fun propose(key: String = "missing:NPC_OP:1", count: Int = 1, params: Map<String, Any?> = mapOf("id" to 1)) =
        service.propose("enrich.npc_op", key, "Talk-to Man", "$count hits", mapOf("count" to count), params = params)

    @Test
    fun `propose creates once and refreshes evidence while open`() {
        val created = propose()
        assertNotNull(created)
        assertNull(propose(count = 5))
        assertEquals(5, service.get(created!!.id).evidence["count"])
        assertEquals(1, service.list().size)
        assertEquals(listOf("inbox.created", "inbox.updated"), bus.flow.replayCache.map { it.type })
    }

    @Test
    fun `go runs the executor and records the result and audit`() {
        val card = propose()!!
        val running = service.go(card.id, dev)
        assertEquals(Decision.GO, running.decision)
        val done = service.get(card.id)
        assertEquals(ActionStatus.DONE, done.status)
        assertEquals("https://w/Man", done.result?.get("pageUrl"))
        assertEquals(listOf(card.id), executed.map { it.id })
        assertEquals("inbox.go", audit.list().single().action)
        assertEquals(false, audit.list().single().details["edited"])
    }

    @Test
    fun `go with changed params counts as an edit`() {
        val card = propose()!!
        service.go(card.id, dev, mapOf("id" to 2))
        val done = service.get(card.id)
        assertEquals(Decision.EDIT, done.decision)
        assertEquals(2, done.params["id"])
        assertEquals(true, audit.list().single().details["edited"])
    }

    @Test
    fun `a failing executor marks the card failed and it can be retried`() {
        val card = propose(params = mapOf("fail" to true))!!
        service.go(card.id, dev)
        val failed = service.get(card.id)
        assertEquals(ActionStatus.FAILED, failed.status)
        assertEquals("nope", failed.error)
        service.go(card.id, dev, mapOf("fail" to false))
        assertEquals(ActionStatus.DONE, service.get(card.id).status)
    }

    @Test
    fun `delete needs a reason and a deleted card is not re-proposed`() {
        val card = propose()!!
        assertThrows(ApiException::class.java) { service.delete(card.id, dev, " ") }
        val deleted = service.delete(card.id, dev, "not worth scripting")
        assertEquals(ActionStatus.DELETED, deleted.status)
        assertEquals("not worth scripting", deleted.decisionReason)
        assertNull(propose(count = 9))
        assertEquals(ActionStatus.DELETED, service.get(card.id).status)
        assertEquals(1, service.get(card.id).evidence["count"])
        assertThrows(ApiException::class.java) { service.go(card.id, dev) }
    }

    @Test
    fun `edit keeps the card pending and go keeps the edit decision`() {
        val card = propose()!!
        val edited = service.edit(card.id, dev, mapOf("id" to 3))
        assertEquals(ActionStatus.PENDING, edited.status)
        assertEquals(Decision.EDIT, edited.decision)
        service.go(card.id, dev)
        assertEquals(Decision.EDIT, service.get(card.id).decision)
        assertEquals(3, executed.single().params["id"])
    }

    @Test
    fun `snoozed cards wake when their time is up`() {
        val card = propose()!!
        service.snooze(card.id, dev, Instant.parse("2026-10-09T13:00:00Z"))
        assertEquals(ActionStatus.SNOOZED, service.get(card.id).status)
        assertEquals(0, service.wakeSnoozed())
        val later = InboxService(InboxStore(db), audit, bus, listOf(executor), CoroutineScope(Dispatchers.Unconfined), Clock.offset(clock, java.time.Duration.ofHours(2)))
        assertEquals(1, later.wakeSnoozed())
        assertEquals(ActionStatus.PENDING, service.get(card.id).status)
        assertNull(service.get(card.id).snoozedUntil)
    }

    @Test
    fun `no executor for a kind fails the card instead of crashing`() {
        val card = service.propose("pipeline.wikisync", "p:1", "Run wikiSync", "")!!
        service.go(card.id, dev)
        val failed = service.get(card.id)
        assertEquals(ActionStatus.FAILED, failed.status)
        assertEquals("No executor for kind pipeline.wikisync", failed.error)
    }
}
