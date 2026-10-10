package org.alter.game.fs

import dev.openrune.cache.CacheManager
import gg.rsmod.util.BuildInfo
import org.alter.game.DevContext
import org.alter.game.GameContext
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.saving.formats.SaveFormatType
import org.alter.game.service.xtea.XteaKeyService
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.rsmod.routefinder.flag.CollisionFlag
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The revision 241 maps index has no group names (one group per region id, terrain in file 0, locs in file 1), so
 * looking squares up by `m<x>_<y>` loaded none and every tile read -1 (blocked). Needs `data/cache`; skips without it.
 */
class MapSquareCollisionTests {
    @Test
    fun `lumbridge loads terrain and loc collision`() {
        val world = World(
            GameContext(
                initialLaunch = false, name = "test", revision = BuildInfo.REVISION, saveFormat = SaveFormatType.JSON, cycleTime = 600,
                playerLimit = 1, home = Tile(3222, 3218), skillCount = 23, npcStatCount = 5, runEnergy = false,
                gItemPublicDelay = 0, gItemDespawnDelay = 0, preloadMaps = false,
            ),
            DevContext(false, false, false, false, false, false),
        )
        world.services.add(XteaKeyService())

        assertTrue(DefinitionSet().createRegion(world, 12850), "region 12850 should load")

        var floorBlocked = 0
        var locBlocked = 0
        for (x in 3200 until 3264) {
            for (z in 3200 until 3264) {
                val flags = world.collision[x, z, 0]
                assertNotEquals(-1, flags, "tile ($x, $z) should be allocated")
                if (flags and CollisionFlag.BLOCK_WALK != 0) floorBlocked++
                if (flags and CollisionFlag.LOC != 0) locBlocked++
            }
        }
        assertTrue(floorBlocked > 0, "terrain should block some floor tiles")
        assertTrue(locBlocked > 0, "locs should block some tiles")

        // Lumbridge castle courtyard (the default home tile) is open floor.
        assertEquals(0, world.collision[3222, 3218, 0])
        // A blocked floor tile and a solid loc tile inside the castle grounds.
        assertTrue(world.collision[3225, 3213, 0] and CollisionFlag.BLOCK_WALK != 0)
        assertTrue(world.collision[3227, 3221, 0] and CollisionFlag.LOC != 0)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadCache() {
            val path = Paths.get("..", "data", "cache")
            // The OSRS cache is not committed; skip (rather than fail) where it hasn't been installed, e.g. CI.
            Assume.assumeTrue("No cache at ${path.toAbsolutePath()}", Files.exists(path.resolve("main_file_cache.dat2")))
            CacheManager.init(path, BuildInfo.REVISION)
        }
    }
}
