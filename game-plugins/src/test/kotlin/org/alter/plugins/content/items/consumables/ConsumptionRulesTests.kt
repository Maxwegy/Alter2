package org.alter.plugins.content.items.consumables

import org.alter.api.Skills
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumptionRulesTests {
    private val table = Consumables.load(java.nio.file.Paths.get("../data/cfg/consumables/consumables.json"))
    private fun c(name: String) = table.byItem.getValue(name)

    private fun state(
        food: Int = 0, combo: Int = 0, potion: Int = 0, attackDelay: Int = 0,
        base: Map<Int, Int> = emptyMap(), current: Map<Int, Int> = emptyMap(), prayerGear: Boolean = false, roll: Double = 0.0,
    ): ConsumerState {
        val b = IntArray(23) { 99 }; val cur = IntArray(23) { 99 }
        base.forEach { (k, v) -> b[k] = v; cur[k] = v }
        current.forEach { (k, v) -> cur[k] = v }
        return ConsumerState(mapOf(Kind.FOOD to food, Kind.COMBO to combo, Kind.POTION to potion, Kind.MIX to food), attackDelay, b, cur, prayerGear, roll)
    }

    private fun apply(name: String, state: ConsumerState): ConsumptionPlan {
        val d = ConsumptionRules.decide(c(name), state)
        assertIs<Decision.Apply>(d, "$name should apply")
        return d.plan
    }

    @Test
    fun `a second standard food on the same tick is blocked, a combo food is not`() {
        val afterShark = state(food = 3)
        assertEquals(Decision.Blocked(Kind.FOOD), ConsumptionRules.decide(c("item.lobster"), afterShark))
        assertIs<Decision.Apply>(ConsumptionRules.decide(c("item.cooked_karambwan"), afterShark))
        assertIs<Decision.Apply>(ConsumptionRules.decide(c("item.saradomin_brew4"), afterShark))
    }

    @Test
    fun `food adds to the attack delay only when one is pending`() {
        assertEquals(0, apply("item.shark", state(attackDelay = 0)).attackDelayAdd)
        assertEquals(3, apply("item.shark", state(attackDelay = 2)).attackDelayAdd)
        assertEquals(2, apply("item.cooked_karambwan", state(attackDelay = 2)).attackDelayAdd)
        assertEquals(0, apply("item.saradomin_brew4", state(attackDelay = 2)).attackDelayAdd)
    }

    @Test
    fun `shark then karambwan stacks to five ticks and the karambwan leaves the potion gate alone`() {
        val shark = apply("item.shark", state(attackDelay = 4))
        val afterShark = state(food = shark.gateTicks, attackDelay = 4 + shark.attackDelayAdd)
        val karambwan = apply("item.cooked_karambwan", afterShark)
        assertEquals(3 + 2, shark.attackDelayAdd + karambwan.attackDelayAdd)
        assertEquals(Kind.COMBO, karambwan.kind)
        assertIs<Decision.Apply>(ConsumptionRules.decide(c("item.saradomin_brew4"), afterShark.copy(gates = afterShark.gates + (Kind.COMBO to karambwan.gateTicks))))
    }

    @Test
    fun `shark, brew and karambwan all apply on one tick`() {
        var s = state(current = mapOf(Skills.HITPOINTS to 40), attackDelay = 4)
        val gates = s.gates.toMutableMap()
        var hp = 40
        for (name in listOf("item.shark", "item.saradomin_brew4", "item.cooked_karambwan")) {
            val plan = apply(name, s.copy(gates = gates, current = s.current.also { it[Skills.HITPOINTS] = hp }))
            gates[plan.kind] = plan.gateTicks
            hp = plan.levels[Skills.HITPOINTS] ?: hp
        }
        assertEquals(40 + 20 + 16 + 18, hp)
    }

    @Test
    fun `heals cap at base unless the food overheals`() {
        assertEquals(99, apply("item.shark", state(current = mapOf(Skills.HITPOINTS to 90))).levels[Skills.HITPOINTS])
        assertNull(apply("item.shark", state()).levels[Skills.HITPOINTS])
        // anglerfish: base 99 heals 22 and may go to 121
        assertEquals(121, apply("item.anglerfish", state()).levels[Skills.HITPOINTS])
        assertEquals(11, apply("item.anglerfish", state(base = mapOf(Skills.HITPOINTS to 50), current = mapOf(Skills.HITPOINTS to 10))).healAmount)
        // brew from base 99: 2 + 14 = 16, overheals
        assertEquals(16, apply("item.saradomin_brew4", state()).healAmount)
        assertEquals(115, apply("item.saradomin_brew4", state()).levels[Skills.HITPOINTS])
    }

    @Test
    fun `brew raises defence and drains attack from the current level`() {
        val plan = apply("item.saradomin_brew4", state(current = mapOf(Skills.ATTACK to 80)))
        assertEquals(99 + 2 + 19, plan.levels[Skills.DEFENCE])
        assertEquals(80 - (8 + 2), plan.levels[Skills.ATTACK])
        assertEquals(99 - (9 + 2), plan.levels[Skills.STRENGTH])
    }

    @Test
    fun `boosts never exceed base plus the boost on a second sip`() {
        val first = apply("item.super_combat_potion4", state())
        assertEquals(99 + 5 + 14, first.levels[Skills.ATTACK])
        val second = apply("item.super_combat_potion3", state(current = first.levels))
        assertNull(second.levels[Skills.ATTACK])
    }

    @Test
    fun `prayer potion restores 7 plus a quarter, 27 percent with prayer gear, never above base`() {
        assertEquals(31, apply("item.prayer_potion4", state(base = mapOf(Skills.PRAYER to 99), current = mapOf(Skills.PRAYER to 0))).levels[Skills.PRAYER])
        assertEquals(33, apply("item.prayer_potion4", state(base = mapOf(Skills.PRAYER to 99), current = mapOf(Skills.PRAYER to 0), prayerGear = true)).levels[Skills.PRAYER])
        assertEquals(99, apply("item.prayer_potion4", state(current = mapOf(Skills.PRAYER to 90))).levels[Skills.PRAYER])
        assertNull(apply("item.prayer_potion4", state()).levels[Skills.PRAYER])
    }

    @Test
    fun `super restore restores every lowered skill except hitpoints`() {
        val plan = apply("item.super_restore4", state(current = mapOf(Skills.ATTACK to 50, Skills.AGILITY to 70, Skills.HITPOINTS to 10, Skills.PRAYER to 0)))
        assertEquals(50 + 8 + 24, plan.levels[Skills.ATTACK])
        assertEquals(99, plan.levels[Skills.AGILITY])
        assertEquals(32, plan.levels[Skills.PRAYER])
        assertNull(plan.levels[Skills.HITPOINTS])
    }

    @Test
    fun `fast foods gate for their own delay and potions for three ticks`() {
        assertEquals(1, apply("item.plain_pizza", state()).gateTicks)
        assertEquals(2, apply("item.half_plain_pizza", state()).gateTicks)
        assertEquals(3, apply("item.shark", state()).gateTicks)
        assertEquals(3, apply("item.saradomin_brew4", state()).gateTicks)
        assertEquals(Decision.Blocked(Kind.POTION), ConsumptionRules.decide(c("item.prayer_potion4"), state(potion = 1)))
    }

    @Test
    fun `a ranged heal uses the roll`() {
        assertEquals(8, apply("item.cave_eel", state(current = mapOf(Skills.HITPOINTS to 1), roll = 0.0)).healAmount)
        assertEquals(12, apply("item.cave_eel", state(current = mapOf(Skills.HITPOINTS to 1), roll = 0.999)).healAmount)
    }

    @Test
    fun `normalisation steps one level toward base on the right beats`() {
        assertEquals(98, Normalisation.step(90, 99))
        assertEquals(91, Normalisation.step(99, 90))
        assertEquals(50, Normalisation.step(50, 50))
        assertTrue(Normalisation.shouldStep(2, boosted = true, preserve = false))
        assertTrue(!Normalisation.shouldStep(2, boosted = true, preserve = true))
        assertTrue(Normalisation.shouldStep(3, boosted = true, preserve = true))
        assertTrue(Normalisation.shouldStep(2, boosted = false, preserve = true))
        assertTrue(!Normalisation.shouldStep(1, boosted = false, preserve = false))
    }
}
