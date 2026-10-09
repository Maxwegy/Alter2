package org.alter.game.model

import org.alter.game.DevContext
import org.alter.game.GameContext
import org.alter.game.saving.formats.SaveFormatType
import kotlin.test.Test
import kotlin.test.assertEquals

class RebootTimerTests {
    private val world = World(
        GameContext(
            initialLaunch = false, name = "test", revision = 0, saveFormat = SaveFormatType.JSON, cycleTime = 600,
            playerLimit = 1, home = Tile(3222, 3218), skillCount = 23, npcStatCount = 5, runEnergy = false,
            gItemPublicDelay = 0, gItemDespawnDelay = 0, preloadMaps = false,
        ),
        DevContext(false, false, false, false, false, false),
    )

    @Test
    fun `the timer counts down and then turns off`() {
        world.rebootTimer = 2
        world.cycleRebootTimer()
        assertEquals(1, world.rebootTimer)
        world.cycleRebootTimer()
        // It used to stay at 0, which made login verification refuse everyone until a manual restart.
        assertEquals(-1, world.rebootTimer)
    }

    @Test
    fun `an inactive timer stays inactive`() {
        world.cycleRebootTimer()
        assertEquals(-1, world.rebootTimer)
    }
}
