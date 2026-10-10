package org.alter.data.spawns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpawnEditsTests {
    private val hansWiki = NpcSpawnEntry(
        npc = "npc.hans", x = 3212, z = 3219, height = 0, walkRadius = 11,
        source = NpcSpawnSource.Wiki("https://oldschool.runescape.wiki/w/Hans", "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}"),
    )
    private val duke = NpcSpawnEntry("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH", NpcSpawnSource.Manual)

    @Test
    fun `an outbox line has a fixed field order and omits an absent direction`() {
        val move = SpawnEdit.of("2026-10-10T12:00:00Z", hansWiki, SpawnPlacement(3215, 3220, 0, 11))
        assertEquals(
            """{"at":"2026-10-10T12:00:00Z","npc":"npc.hans","from":{"x":3212,"z":3219,"height":0,"walkRadius":11},""" +
                """"to":{"x":3215,"z":3220,"height":0,"walkRadius":11},"regionId":12850,"source":"https://oldschool.runescape.wiki/w/Hans"}""",
            SpawnEdits.line(move),
        )
    }

    @Test
    fun `a removal writes to as null and a manual entry's source as manual`() {
        val remove = SpawnEdit.of("2026-10-10T12:00:01Z", duke, null)
        assertEquals(
            """{"at":"2026-10-10T12:00:01Z","npc":"npc.duke_of_lumbridge","from":{"x":3212,"z":3220,"height":1,"walkRadius":4,"direction":"SOUTH"},""" +
                """"to":null,"regionId":12850,"source":"manual"}""",
            SpawnEdits.line(remove),
        )
    }

    @Test
    fun `lines parse back to the same edits, with their line numbers`() {
        val edits = listOf(
            SpawnEdit.of("2026-10-10T12:00:00Z", hansWiki, SpawnPlacement(3215, 3220, 0, 11, "EAST")),
            SpawnEdit.of("2026-10-10T12:00:01Z", duke, null),
        )
        val parsed = SpawnEdits.parse(edits.joinToString("\n", postfix = "\n\n") { SpawnEdits.line(it) })
        assertEquals(emptyList(), parsed.errors)
        assertEquals(listOf(IndexedValue(1, edits[0]), IndexedValue(2, edits[1])), parsed.edits)
    }

    @Test
    fun `bad lines are reported with their line number`() {
        val good = SpawnEdits.line(SpawnEdit.of("2026-10-10T12:00:00Z", duke, null))
        val parsed = SpawnEdits.parse("$good\n{ nope\n{\"at\":\"x\",\"npc\":\"npc.hans\",\"from\":{\"x\":1},\"regionId\":1,\"source\":\"manual\"}\n")
        assertEquals(1, parsed.edits.size)
        assertTrue(parsed.errors.any { it.startsWith("line 2: not valid JSON") }, parsed.errors.toString())
        assertTrue("line 3: from.z must be an integer" in parsed.errors, parsed.errors.toString())
        assertTrue("line 3: to must be an object or null" in parsed.errors, parsed.errors.toString())
    }
}
