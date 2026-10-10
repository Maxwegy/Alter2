package org.alter.data.spawns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpawnIdsTests {
    @Test
    fun `wiki ids hash the page, npc and nominal tile`() {
        assertEquals("w06bd7d9b22e4", SpawnIds.wiki("https://oldschool.runescape.wiki/w/Hans", "npc.hans", 3212, 3219, 0))
        assertNotEquals(SpawnIds.wiki("https://oldschool.runescape.wiki/w/Hans", "npc.hans", 3212, 3219, 1), SpawnIds.wiki("https://oldschool.runescape.wiki/w/Hans", "npc.hans", 3212, 3219, 0))
    }

    @Test
    fun `minted ids hash the npc, tile and salt`() {
        assertEquals("m56d8a069f0b8", SpawnIds.minted("npc.hans", 3221, 3219, 0, SpawnIds.MIGRATION_SALT))
        assertEquals("me4c8acb121d1", SpawnIds.minted("npc.man", 3222, 3218, 0, "2026-10-10T12:00:00Z"))
        assertNotEquals(SpawnIds.minted("npc.man", 3222, 3218, 0, "2026-10-10T12:00:01Z"), SpawnIds.minted("npc.man", 3222, 3218, 0, "2026-10-10T12:00:00Z"))
    }

    @Test
    fun `ids match the pattern`() {
        assertTrue(SpawnIds.isValid("w06bd7d9b22e4"))
        assertTrue(SpawnIds.isValid("m56d8a069f0b8"))
        listOf("", "x06bd7d9b22e4", "w06bd7d9b22e", "w06bd7d9b22e4a", "W06BD7D9B22E4", "w06bd7d9b22g4").forEach { assertFalse(SpawnIds.isValid(it), it) }
    }
}
