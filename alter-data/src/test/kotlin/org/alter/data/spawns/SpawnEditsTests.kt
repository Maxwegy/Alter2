package org.alter.data.spawns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnEditsTests {
    private val hansWiki = Entries.hansWiki
    private val duke = Entries.manual("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH")

    @Test
    fun `an outbox line has a fixed field order and omits an absent direction`() {
        val move = SpawnEdit.of("2026-10-10T12:00:00Z", hansWiki, SpawnPlacement(3215, 3220, 0, 11))
        assertEquals(
            """{"at":"2026-10-10T12:00:00Z","npc":"npc.hans","id":"w06bd7d9b22e4","from":{"x":3212,"z":3219,"height":0,"walkRadius":11},""" +
                """"to":{"x":3215,"z":3220,"height":0,"walkRadius":11},"regionId":12850,"source":"https://oldschool.runescape.wiki/w/Hans"}""",
            SpawnEdits.line(move),
        )
    }

    @Test
    fun `a removal writes to as null and a manual entry's source as manual`() {
        val remove = SpawnEdit.of("2026-10-10T12:00:01Z", duke, null)
        assertEquals(
            """{"at":"2026-10-10T12:00:01Z","npc":"npc.duke_of_lumbridge","id":"${duke.id}","from":{"x":3212,"z":3220,"height":1,"walkRadius":4,"direction":"SOUTH"},""" +
                """"to":null,"regionId":12850,"source":"manual"}""",
            SpawnEdits.line(remove),
        )
    }

    @Test
    fun `an add writes from as null and mints its id from the instant`() {
        val add = SpawnEdit.add("2026-10-10T12:00:00Z", "npc.man", SpawnPlacement(3222, 3218, 0, 2))
        assertEquals("me4c8acb121d1", add.id)
        assertEquals(
            """{"at":"2026-10-10T12:00:00Z","npc":"npc.man","id":"me4c8acb121d1","from":null,"to":{"x":3222,"z":3218,"height":0,"walkRadius":2},"regionId":12850,"source":"edit"}""",
            SpawnEdits.line(add),
        )
    }

    @Test
    fun `lines parse back to the same edits, with their line numbers`() {
        val edits = listOf(
            SpawnEdit.of("2026-10-10T12:00:00Z", hansWiki, SpawnPlacement(3215, 3220, 0, 11, "EAST")),
            SpawnEdit.of("2026-10-10T12:00:01Z", duke, null),
            SpawnEdit.add("2026-10-10T12:00:02Z", "npc.man", SpawnPlacement(3222, 3218, 0, 2)),
        )
        val parsed = SpawnEdits.parse(edits.joinToString("\n", postfix = "\n\n") { SpawnEdits.line(it) })
        assertEquals(emptyList(), parsed.errors)
        assertEquals(edits.mapIndexed { i, e -> IndexedValue(i + 1, e) }, parsed.edits)
    }

    @Test
    fun `version-1 lines without an id still parse`() {
        val v1 = """{"at":"2026-10-10T12:00:00Z","npc":"npc.hans","from":{"x":3221,"z":3219,"height":0,"walkRadius":0,"direction":"EAST"},""" +
            """"to":{"x":3221,"z":3219,"height":0,"walkRadius":5,"direction":"EAST"},"regionId":12850,"source":"manual"}"""
        val parsed = SpawnEdits.parse(v1)
        assertEquals(emptyList(), parsed.errors)
        assertNull(parsed.edits.single().value.id)
        assertEquals(5, parsed.edits.single().value.to!!.walkRadius)
    }

    @Test
    fun `bad lines are reported with their line number`() {
        val good = SpawnEdits.line(SpawnEdit.of("2026-10-10T12:00:00Z", duke, null))
        val parsed = SpawnEdits.parse(
            "$good\n{ nope\n{\"at\":\"x\",\"npc\":\"npc.hans\",\"from\":{\"x\":1},\"regionId\":1,\"source\":\"manual\"}\n" +
                "{\"at\":\"x\",\"npc\":\"npc.hans\",\"id\":\"w06bd7d9b22e4\",\"from\":null,\"to\":null,\"regionId\":1,\"source\":\"manual\"}\n" +
                "{\"at\":\"x\",\"npc\":\"npc.hans\",\"id\":\"hans\",\"from\":null,\"to\":{\"x\":1,\"z\":1,\"height\":0,\"walkRadius\":0},\"regionId\":1,\"source\":\"edit\"}\n" +
                "{\"at\":\"x\",\"npc\":\"npc.hans\",\"from\":null,\"to\":{\"x\":1,\"z\":1,\"height\":0,\"walkRadius\":0},\"regionId\":1,\"source\":\"edit\"}\n",
        )
        assertEquals(1, parsed.edits.size)
        assertTrue(parsed.errors.any { it.startsWith("line 2: not valid JSON") }, parsed.errors.toString())
        assertTrue("line 3: from.z must be an integer" in parsed.errors, parsed.errors.toString())
        assertTrue("line 3: to must be an object or null" in parsed.errors, parsed.errors.toString())
        assertEquals(listOf("line 4: from and to cannot both be null"), parsed.errors.filter { it.startsWith("line 4") })
        assertTrue(parsed.errors.any { it.startsWith("line 5: id must match") }, parsed.errors.toString())
        assertTrue("line 6: an add (from null) needs an id" in parsed.errors, parsed.errors.toString())
    }
}
