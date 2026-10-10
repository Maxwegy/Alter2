package org.alter.plugins.content.infrastructure.spawns

import org.alter.data.spawns.SpawnRules
import org.alter.game.model.Tile
import kotlin.test.Test
import kotlin.test.assertEquals

/** alter-data cannot import the engine, so it repeats `Tile.regionId`; this pins the two together. */
class RegionIdParityTests {
    private val coordinates = listOf(
        3212 to 3219, 3211 to 3247, 3254 to 3428, 3196 to 3263,
        3264 to 3232, 3263 to 3232, 3199 to 3263, 3200 to 3263,
        2591 to 4730, 1664 to 3669, 0 to 0, 3200 to 3200,
    )

    @Test
    fun `SpawnRules regionId equals Tile regionId`() {
        coordinates.forEach { (x, z) -> assertEquals(Tile(x, z).regionId, SpawnRules.regionId(x, z), "($x, $z)") }
    }

    @Test
    fun `regionBase is in its own region`() {
        coordinates.forEach { (x, z) ->
            val region = Tile(x, z).regionId
            val base = SpawnRules.regionBase(region)
            assertEquals(region, Tile(base.x, base.z).regionId, "($x, $z)")
        }
    }
}
