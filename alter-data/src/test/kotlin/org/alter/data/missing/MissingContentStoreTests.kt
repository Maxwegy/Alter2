package org.alter.data.missing

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingContentStoreTests {
    private val file = Files.createTempDirectory("missing").resolve("missing_content.json")
    private val clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC)
    private val talkToMan = MissingContentEvent("NPC_OP", id = 3108, op = 1, name = "Man", optionName = "Talk-to", location = Location(3222, 3218, 0))

    @Test
    fun `repeated events aggregate under one key`() {
        val store = MissingContentStore(file, clock = clock)
        assertTrue(store.record(talkToMan))
        assertFalse(store.record(talkToMan.copy(location = Location(3223, 3218, 0))))
        val entry = store.snapshot().single()
        assertEquals(2, entry.count)
        assertEquals(listOf(Location(3222, 3218, 0), Location(3223, 3218, 0)), entry.locations)
        assertEquals("NPC_OP:3108:-1:1:-1", entry.key)
    }

    @Test
    fun `locations are capped and deduplicated`() {
        val store = MissingContentStore(file, maxLocationsPerEntry = 2, clock = clock)
        repeat(5) { store.record(talkToMan.copy(location = Location(3200 + it, 3200, 0))) }
        store.record(talkToMan.copy(location = Location(3200, 3200, 0)))
        assertEquals(2, store.snapshot().single().locations.size)
    }

    @Test
    fun `entries are ordered by count`() {
        val store = MissingContentStore(file, clock = clock)
        store.record(talkToMan)
        repeat(3) { store.record(MissingContentEvent("LOC_OP", id = 1530, op = 1)) }
        assertEquals(listOf("LOC_OP", "NPC_OP"), store.snapshot().map { it.type })
    }

    @Test
    fun `flush writes only when dirty and survives a reload`() {
        val store = MissingContentStore(file, clock = clock)
        assertFalse(store.flushIfDirty())
        store.record(talkToMan)
        assertTrue(store.flushIfDirty())
        assertFalse(store.flushIfDirty())

        val reloaded = MissingContentStore(file, clock = clock).apply { load() }
        reloaded.record(talkToMan)
        assertEquals(2, reloaded.snapshot().single().count)
        assertTrue(Files.readString(file).contains("\"schemaVersion\" : 1"))
    }

    @Test
    fun `a corrupt file starts a fresh log instead of failing`() {
        Files.writeString(file, "{ not json")
        val store = MissingContentStore(file, clock = clock).apply { load() }
        assertEquals(0, store.size())
    }
}
