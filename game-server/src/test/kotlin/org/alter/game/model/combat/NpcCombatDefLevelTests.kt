package org.alter.game.model.combat

import org.alter.game.model.entity.Npc
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcCombatDefLevelTests {
    @Test
    fun `levels are applied to max and current`() {
        val stats = Npc.Stats(5)
        stats.setCurrentLevel(0, 3)
        NpcCombatDef.DEFAULT.copy(attack = 97, strength = 67, defence = 135, magic = 1, ranged = 1).applyLevelsTo(stats)
        assertEquals(listOf(97, 67, 135, 1, 1), (0 until 5).map(stats::getMaxLevel))
        assertEquals(listOf(97, 67, 135, 1, 1), (0 until 5).map(stats::getCurrentLevel))
    }

    @Test
    fun `the default definition gives level 1, never 0`() {
        val stats = Npc.Stats(5)
        NpcCombatDef.DEFAULT.applyLevelsTo(stats)
        assertEquals(List(5) { 1 }, (0 until 5).map(stats::getMaxLevel))
        assertEquals(1, NpcCombatDef.DEFAULT.copy(attack = 0).statLevels()[0])
    }

    @Test
    fun `smaller stat sets are not overrun`() {
        val stats = Npc.Stats(3)
        NpcCombatDef.DEFAULT.copy(attack = 5, strength = 6, defence = 7, magic = 8, ranged = 9).applyLevelsTo(stats)
        assertEquals(listOf(5, 6, 7), (0 until 3).map(stats::getMaxLevel))
    }
}
