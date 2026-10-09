package org.alter.api.dsl

import org.alter.api.NpcSkills
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcCombatDslTests {
    private fun build(stats: NpcCombatDsl.StatsBuilder.() -> Unit) = NpcCombatDsl.Builder().apply {
        configs {
            attackSpeed = 4
            respawnDelay = 10
        }
        stats(stats)
        anims { death = 836 }
    }.build()

    @Test
    fun `assigned levels reach the definition`() {
        val def = build {
            hitpoints = 50
            attack = 40
            strength = 41
            defence = 42
            magic = 43
            ranged = 44
        }
        assertEquals(50, def.hitpoints)
        assertEquals(listOf(40, 41, 42, 43, 44), listOf(def.attack, def.strength, def.defence, def.magic, def.ranged))
    }

    @Test
    fun `pair syntax works too`() {
        assertEquals(40, build { +(NpcSkills.ATTACK to 40) }.attack)
    }

    @Test
    fun `unset levels default to 1`() {
        assertEquals(1, build { hitpoints = 10 }.defence)
    }

    @Test
    fun `stat levels follow the NpcSkills slot order`() {
        val levels = build {
            attack = 40
            strength = 41
            defence = 42
            magic = 43
            ranged = 44
        }.statLevels()
        assertEquals(40, levels[NpcSkills.ATTACK])
        assertEquals(41, levels[NpcSkills.STRENGTH])
        assertEquals(42, levels[NpcSkills.DEFENCE])
        assertEquals(43, levels[NpcSkills.MAGIC])
        assertEquals(44, levels[NpcSkills.RANGED])
    }
}
