package org.alter.data.npcs

import org.alter.data.snapshot.NpcBonuses
import org.alter.data.snapshot.NpcEntry
import org.alter.data.snapshot.NpcImmunities
import org.alter.data.snapshot.NpcLevels
import org.alter.data.snapshot.NpcSlayer
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcDefSpecTests {
    private val demon = NpcEntry(
        source = "Abyssal demon#Standard", ids = listOf(415), hitpoints = 150, attackSpeed = 4, respawnTicks = 12,
        levels = NpcLevels(attack = 97, strength = 67, defence = 135, ranged = 1, magic = 1),
        bonuses = NpcBonuses(stabDefence = 20, slashDefence = 20, crushDefence = 20, rangedDefence = 20),
        aggressive = false, immunities = NpcImmunities(poison = true), attributes = listOf("demon"),
        slayer = NpcSlayer(level = 85, xp = 150.0),
    )

    @Test
    fun `wiki fills everything when the cache has nothing`() {
        val spec = NpcDefSpec.merge(demon, cache = null)
        assertEquals(listOf(150, 97, 67, 135, 1, 1), listOf(spec.hitpoints, spec.attack, spec.strength, spec.defence, spec.magic, spec.ranged))
        assertEquals(20, spec.bonuses[5])
        assertEquals(12, spec.respawnTicks)
        assertEquals(85, spec.slayerLevel)
        assertEquals(true, spec.immunePoison)
        assertEquals(NpcDefSpec.WIKI, spec.sources["attack"])
    }

    @Test
    fun `cache values win field by field`() {
        // Cache order: attack, defence, strength, hitpoints, ranged, magic.
        val cache = NpcCacheStats.of(attack = 100, defence = 120, strength = 70, hitpoints = 160, ranged = 1, magic = 1, params = mapOf(5 to 25, 10 to 5, 14 to 5))
        val spec = NpcDefSpec.merge(demon, cache)
        assertEquals(100, spec.attack)
        assertEquals(120, spec.defence)
        assertEquals(160, spec.hitpoints)
        assertEquals(25, spec.bonuses[5])
        assertEquals(20, spec.bonuses[6])
        assertEquals(5, spec.bonuses[11])
        assertEquals(5, spec.attackSpeed)
        assertEquals(NpcDefSpec.CACHE, spec.sources["attack"])
        assertEquals("cache+wiki", spec.sources["bonuses"])
    }

    @Test
    fun `an all-ones cache entry counts as absent`() {
        val cache = NpcCacheStats.of(1, 1, 1, 1, 1, 1, emptyMap())
        assertEquals(null, cache.levels)
        assertEquals(97, NpcDefSpec.merge(demon, cache).attack)
    }
}
