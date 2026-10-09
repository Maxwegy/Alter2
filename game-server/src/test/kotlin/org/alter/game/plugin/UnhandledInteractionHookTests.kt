package org.alter.game.plugin

import org.alter.game.DevContext
import org.alter.game.GameContext
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.saving.formats.SaveFormatType
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnhandledInteractionHookTests {
    private val repository = PluginRepository(world)
    private val interaction = UnhandledInteraction(InteractionType.NPC_OP, id = 3108, rawId = 3106, op = 1, tile = Tile(3222, 3218))

    @Test
    fun `without a hook nothing happens`() {
        repository.executeUnhandledInteraction(player, interaction)
    }

    @Test
    fun `bound hook receives the player and payload`() {
        var received: Pair<Player, UnhandledInteraction>? = null
        repository.bindUnhandledInteraction { p, i -> received = p to i }
        repository.executeUnhandledInteraction(player, interaction)
        assertEquals(player to interaction, received)
    }

    @Test
    fun `a throwing hook is swallowed`() {
        repository.bindUnhandledInteraction { _, _ -> error("boom") }
        repository.executeUnhandledInteraction(player, interaction)
    }

    @Test
    fun `binding twice is rejected`() {
        repository.bindUnhandledInteraction { _, _ -> }
        assertFailsWith<IllegalStateException> { repository.bindUnhandledInteraction { _, _ -> } }
    }

    @Test
    fun `payload defaults mark absent fields`() {
        val minimal = UnhandledInteraction(InteractionType.IF_BUTTON, id = 149)
        assertEquals(149, minimal.rawId)
        assertEquals(-1, minimal.op)
        assertNull(minimal.tile)
    }

    companion object {
        private lateinit var world: World
        private lateinit var player: Player

        @BeforeClass
        @JvmStatic
        fun createWorld() {
            // A World needs no cache to construct; it allocates a large collision map, so share one per class.
            world = World(
                GameContext(
                    initialLaunch = false,
                    name = "test",
                    revision = 0,
                    saveFormat = SaveFormatType.JSON,
                    cycleTime = 600,
                    playerLimit = 1,
                    home = Tile(3222, 3218),
                    skillCount = 23,
                    npcStatCount = 5,
                    runEnergy = false,
                    gItemPublicDelay = 0,
                    gItemDespawnDelay = 0,
                    preloadMaps = false,
                ),
                DevContext(false, false, false, false, false, false),
            )
            player = Player(world)
        }
    }
}
