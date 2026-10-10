package org.alter.data.spawns

import kotlin.test.Test
import kotlin.test.assertEquals

class SpawnRulesTests {
    @Test
    fun `regionId matches the engine formula on the plan's coordinates`() {
        assertEquals(12850, SpawnRules.regionId(3212, 3219)) // Hans
        assertEquals(12850, SpawnRules.regionId(3211, 3247)) // Shop keeper (Lumbridge)
        assertEquals(12853, SpawnRules.regionId(3254, 3428)) // Town Crier, Varrock
        assertEquals(12594, SpawnRules.regionId(3196, 3263))
        // Region boundaries: x 3263 | 3264 and x 3199 | 3200.
        assertEquals(13106, SpawnRules.regionId(3264, 3232))
        assertEquals(12850, SpawnRules.regionId(3263, 3232))
        assertEquals(12594, SpawnRules.regionId(3199, 3263))
        assertEquals(12850, SpawnRules.regionId(3200, 3263))
        assertEquals(10313, SpawnRules.regionId(2591, 4730)) // thieving test area
    }

    @Test
    fun `regionBase is the region's south-west tile`() {
        assertEquals(SpawnPoint(3200, 3200), SpawnRules.regionBase(12850))
        assertEquals(12850, SpawnRules.regionBase(12850).let { SpawnRules.regionId(it.x, it.z) })
        assertEquals(12850, SpawnRules.regionId(3200 + 63, 3200 + 63))
    }

    @Test
    fun `walkRadius per Template Map shape`() {
        assertEquals(0, SpawnRules.walkRadius(MapShape.Pin))
        assertEquals(11, SpawnRules.walkRadius(MapShape.Rectangle(23, 31))) // Hans
        assertEquals(10, SpawnRules.walkRadius(MapShape.Rectangle())) // defaults 20x20
        assertEquals(10, SpawnRules.walkRadius(MapShape.Rectangle(20, 30)))
        assertEquals(5, SpawnRules.walkRadius(MapShape.Rectangle(23, 31, r = 5)))
        assertEquals(4, SpawnRules.walkRadius(MapShape.Square(4))) // Town Crier
        assertEquals(10, SpawnRules.walkRadius(MapShape.Circle()))
    }

    @Test
    fun `Hans's rectangle covers the Module Map bounds`() {
        assertEquals(SpawnBounds(3201, 3224, 3204, 3235), SpawnRules.bounds(SpawnPoint(3212, 3219), MapShape.Rectangle(23, 31)))
        assertEquals(SpawnBounds(3211, 3211, 3247, 3247), SpawnRules.bounds(SpawnPoint(3211, 3247), MapShape.Pin))
        assertEquals(SpawnBounds(3250, 3258, 3424, 3432), SpawnRules.bounds(SpawnPoint(3254, 3428), MapShape.Square(4)))
    }
}
