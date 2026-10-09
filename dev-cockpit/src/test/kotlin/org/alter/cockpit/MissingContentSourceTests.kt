package org.alter.cockpit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.inbox.sources.MissingContentSource
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.Database
import org.alter.cockpit.store.InboxStore
import org.alter.cockpit.wiki.PageResolver
import org.alter.data.config.InfraConfig
import org.alter.data.missing.Location
import org.alter.data.missing.MissingContentEntry
import org.alter.data.missing.MissingContentEvent
import org.alter.data.missing.MissingContentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class MissingContentSourceTests {
    private val db = Database.inMemory()
    private val inbox = InboxService(InboxStore(db), AuditStore(db), EventBus(), emptyList(), CoroutineScope(Dispatchers.Unconfined))
    private val file = Files.createTempDirectory("missing").resolve("missing_content.json")
    private val resolver = PageResolver(InfraConfig.Wiki())
    private val planner = { params: Map<String, Any?> ->
        PageResolver.lookupType(params["type"] as String)?.let { listOf(PlanStep(PlanStep.REQUEST, "lookup", resolver.lookupUrl(it, params["id"] as Int))) }.orEmpty()
    }
    private val source = MissingContentSource(file, inbox, planner, minCount = 2)

    private fun entry(type: String, id: Int, count: Long, name: String? = "Man", option: String? = "Talk-to") = MissingContentEntry(
        key = "$type:$id:-1:1:-1", type = type, id = id, rawId = id, op = 1, usedId = -1, component = -1, name = name, optionName = option,
        count = count, firstSeen = "2026-10-01T10:00:00Z", lastSeen = "2026-10-09T18:30:00Z", locations = listOf(Location(3222, 3218, 0)),
    )

    private fun write(vararg entries: MissingContentEntry, modified: Long = 1_000) {
        Json.mapper.writeValue(file.toFile(), MissingContentFile(updatedAt = "2026-10-09T18:30:00Z", entries = entries.toList()))
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified))
    }

    @Test
    fun `the file becomes one card per key above the threshold`() {
        write(entry("NPC_OP", 3108, 7), entry("LOC_OP", 1530, 1, "Door", "Open"), entry("NPC_NO_DROPS", 2, 3, "Cow", null))
        assertEquals(2, source.pollFile())
        val cards = inbox.list().sortedBy { it.title }
        assertEquals(listOf("Cow (2) has no drop table", "Talk-to on Man (3108) is unscripted"), cards.map { it.title })
        val man = cards.last()
        assertEquals("enrich.npc_op", man.kind)
        assertEquals("missing:NPC_OP:3108:-1:1:-1", man.sourceKey)
        assertEquals(7, man.evidence["count"])
        assertEquals("npc", man.params["lookupType"])
        assertEquals("https://oldschool.runescape.wiki/w/Special:Lookup?type=npc&id=3108", man.plan.single().target)
        assertTrue(man.summary.startsWith("7× since 2026-10-01"))
        assertTrue(man.summary.endsWith("near 3222,3218,0"))
    }

    @Test
    fun `unchanged files are not re-read and changed counts refresh the card`() {
        write(entry("NPC_OP", 3108, 7))
        assertEquals(1, source.pollFile())
        assertEquals(0, source.pollFile())
        write(entry("NPC_OP", 3108, 8), modified = 2_000)
        assertEquals(0, source.pollFile())
        assertEquals(8, inbox.list().single().evidence["count"])
    }

    @Test
    fun `a live event creates the card immediately when the threshold allows`() {
        val lenient = MissingContentSource(file, inbox, planner, minCount = 1)
        lenient.onLiveEvent(MissingContentEvent("LOC_OP", 1530, op = 1, name = "Door", optionName = "Open", location = Location(1, 2, 0)))
        val card = inbox.list().single()
        assertEquals("Open on Door (1530) is unscripted", card.title)
        assertEquals("object", card.params["lookupType"])
        assertEquals(1, card.evidence["count"])
        source.onLiveEvent(MissingContentEvent("IF_BUTTON", 99, component = 5))
        assertEquals(1, inbox.list().size)
    }

    @Test
    fun `interface interactions get a card without a wiki step`() {
        val lenient = MissingContentSource(file, inbox, planner, minCount = 1)
        lenient.onLiveEvent(MissingContentEvent("IF_BUTTON", 99, component = 5))
        val card = inbox.list().single()
        assertEquals("Option -1 on IF_BUTTON 99 is unscripted", card.title)
        assertTrue(card.plan.isEmpty())
        assertEquals(null, card.params["lookupType"])
    }
}
