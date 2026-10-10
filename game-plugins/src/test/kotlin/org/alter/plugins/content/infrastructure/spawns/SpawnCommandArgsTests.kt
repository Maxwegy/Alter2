package org.alter.plugins.content.infrastructure.spawns

import org.alter.game.model.Direction
import org.alter.plugins.content.infrastructure.spawns.SpawnCommandArgs.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpawnCommandArgsTests {
    @Test
    fun `setwander takes one non-negative whole number`() {
        assertEquals(Parsed.Ok(0), SpawnCommandArgs.walkRadius(arrayOf("0")))
        assertEquals(Parsed.Ok(11), SpawnCommandArgs.walkRadius(arrayOf("11")))
        listOf(arrayOf(), arrayOf("-1"), arrayOf("two"), arrayOf("1.5"), arrayOf("1", "2")).forEach { args ->
            val error = assertIs<Parsed.Error>(SpawnCommandArgs.walkRadius(args), args.joinToString())
            assertTrue(error.message.startsWith("Usage: ::setwander <n>"))
        }
    }

    @Test
    fun `setdirection takes one compass name in any case`() {
        assertEquals(Parsed.Ok(Direction.NORTH_EAST), SpawnCommandArgs.direction(arrayOf("NORTH_EAST")))
        assertEquals(Parsed.Ok(Direction.NORTH_EAST), SpawnCommandArgs.direction(arrayOf("north-east")))
        assertEquals(Parsed.Ok(Direction.WEST), SpawnCommandArgs.direction(arrayOf("west")))
        listOf(arrayOf(), arrayOf("NONE"), arrayOf("UP"), arrayOf("north", "east")).forEach { args ->
            val error = assertIs<Parsed.Error>(SpawnCommandArgs.direction(args), args.joinToString())
            assertTrue("NORTH_WEST, NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST" in error.message, error.message)
        }
    }

    @Test
    fun `argument-free commands reject arguments`() {
        assertEquals(Parsed.Ok(Unit), SpawnCommandArgs.none("spawninfo", arrayOf()))
        assertIs<Parsed.Error>(SpawnCommandArgs.none("spawninfo", arrayOf("x")))
    }
}
