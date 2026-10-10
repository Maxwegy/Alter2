package org.alter.plugins.content.combat.specialattack

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SpecialRules] against the committed data file, so the expected values check the file as well as the rules. */
class SpecialRulesTests {
    private val table = SpecialAttackDefs.load(Paths.get("../data/cfg/combat/special_attacks.json"))

    private fun def(item: String) = table.byItem.getValue("item.$item")

    private fun fire(def: SpecialDef, state: SpecialState): SpecialPlan =
        (SpecialRules.decide(def, state) as? SpecialDecision.Fire)?.plan ?: error("expected the special to fire")

    /** Every hit lands: chance 1.0 for each multiplier, rolls of 0. */
    private fun sure(def: SpecialDef, base: Int, energy: Int = 100, vsNpc: Boolean = true, size: Int = 1, missingPrayer: Int = 0) = SpecialState(
        energy = energy, vsNpc = vsNpc, targetSize = size, baseMaxHit = base, missingPrayer = missingPrayer,
        accuracy = SpecialRules.accuracyMultipliers(def).associateWith { 1.0 }, rolls = def.hits.map { 0.0 },
    )

    private fun after(def: SpecialDef, plan: SpecialPlan, damages: List<Int>, target: TargetState = TargetState(emptyMap(), isPlayer = false), self: Map<String, Level> = emptyMap(), selfRun: Int = 0, roll: Double = 0.0) =
        SpecialRules.afterRoll(def, plan, damages, AfterRollState(self, selfRun, target, roll))

    private fun missed(plan: SpecialPlan) = plan.copy(hits = plan.hits.map { it.copy(landed = false) })

    @Test
    fun `puncture costs 25, hits twice for x1_15 and delays the second hit against NPCs only`() {
        val dds = def("dragon_dagger")
        val plan = fire(dds, sure(dds, base = 40))
        assertEquals(75, plan.energyAfter)
        assertEquals(listOf(46, 46), plan.hits.map { it.maxHit })
        assertEquals(listOf(1, 2), plan.hits.map { it.delay })
        assertEquals(listOf(1, 1), fire(dds, sure(dds, base = 40, vsNpc = false)).hits.map { it.delay })
        val rolled = fire(dds, sure(dds, base = 40).copy(accuracy = mapOf(1.15 to 0.5), rolls = listOf(0.1, 0.9)))
        assertEquals(listOf(true, false), rolled.hits.map { it.landed })
    }

    @Test
    fun `abyssal puncture needs 25 energy, hits for x0_85 and shares one roll`() {
        val dagger = def("abyssal_dagger")
        assertEquals(SpecialDecision.Refused(RefusalReason.NOT_ENOUGH_ENERGY), SpecialRules.decide(dagger, sure(dagger, base = 40, energy = 24)))
        val plan = fire(dagger, sure(dagger, base = 40, energy = 25))
        assertEquals(0, plan.energyAfter)
        assertEquals(listOf(34, 34), plan.hits.map { it.maxHit })
        val shared = fire(dagger, sure(dagger, base = 40).copy(accuracy = mapOf(1.25 to 0.5), rolls = listOf(0.1, 0.9)))
        assertEquals(listOf(true, true), shared.hits.map { it.landed })
        val sharedMiss = fire(dagger, sure(dagger, base = 40).copy(accuracy = mapOf(1.25 to 0.5), rolls = listOf(0.9, 0.1)))
        assertEquals(listOf(false, false), sharedMiss.hits.map { it.landed })
    }

    @Test
    fun `the AGS floors after each multiplier and doubles accuracy`() {
        val ags = def("armadyl_godsword")
        assertEquals(58, fire(ags, sure(ags, base = 43)).hits.single().maxHit)
        assertEquals(setOf(2.0), SpecialRules.accuracyMultipliers(ags))
        assertEquals(ags, def("armadyl_godsword_or"))
    }

    @Test
    fun `the BGS drains by damage in order`() {
        val bgs = def("bandos_godsword")
        val plan = fire(bgs, sure(bgs, base = 40))
        val target = TargetState(mapOf("defence" to Level(20, 20), "strength" to Level(0, 0), "prayer" to Level(20, 20), "attack" to Level(50, 50)), isPlayer = true)
        val out = after(bgs, plan, listOf(80), target)
        assertEquals(mapOf("defence" to 0, "prayer" to 0, "attack" to 10), out.targetLevels)
        assertEquals(emptyMap(), after(bgs, plan, listOf(0), target).targetLevels)
    }

    @Test
    fun `the SGS heals half the damage and a quarter as prayer, rounded up with minimums, only on a hit`() {
        val sgs = def("saradomin_godsword")
        val heal = sgs.effects.filterIsInstance<SpecialEffect.HealSelf>().single()
        assertEquals(30 to 15, SpecialRules.heal(heal, 60))
        assertEquals(10 to 5, SpecialRules.heal(heal, 10))
        assertEquals(17 to 9, SpecialRules.heal(heal, 33))
        val plan = fire(sgs, sure(sgs, base = 40))
        val self = mapOf("hitpoints" to Level(50, 99), "prayer" to Level(90, 99))
        assertEquals(mapOf("hitpoints" to 80, "prayer" to 99), after(sgs, plan, listOf(60), self = self).selfLevels)
        assertEquals(emptyMap(), after(sgs, missed(plan), listOf(0), self = self).selfLevels)
    }

    @Test
    fun `the ZGS freezes for 32 ticks on a hit`() {
        val zgs = def("zamorak_godsword")
        val plan = fire(zgs, sure(zgs, base = 40))
        assertEquals(32, after(zgs, plan, listOf(0)).freezeTicks)
        assertNull(after(zgs, missed(plan), listOf(0)).freezeTicks)
    }

    @Test
    fun `the DWH drains 30 percent of current defence when it deals damage`() {
        val dwh = def("dragon_warhammer")
        val plan = fire(dwh, sure(dwh, base = 41))
        assertEquals(61, plan.hits.single().maxHit)
        val first = after(dwh, plan, listOf(10), TargetState(mapOf("defence" to Level(75, 75)), isPlayer = false)).targetLevels
        assertEquals(mapOf("defence" to 53), first)
        val second = after(dwh, plan, listOf(10), TargetState(mapOf("defence" to Level(53, 75)), isPlayer = false)).targetLevels
        assertEquals(mapOf("defence" to 38), second)
        assertEquals(emptyMap(), after(dwh, plan, listOf(0), TargetState(mapOf("defence" to Level(75, 75)), isPlayer = false)).targetLevels)
    }

    @Test
    fun `the bludgeon adds half a percent per missing prayer point`() {
        val bludgeon = def("abyssal_bludgeon")
        assertEquals(45, fire(bludgeon, sure(bludgeon, base = 45, missingPrayer = 0)).hits.single().maxHit)
        assertEquals(54, fire(bludgeon, sure(bludgeon, base = 45, missingPrayer = 40)).hits.single().maxHit)
        assertEquals(50, fire(bludgeon, sure(bludgeon, base = 50)).energyAfter)
    }

    @Test
    fun `arclight drains 5 percent of base plus 1, 10 percent against demons`() {
        val arclight = def("arclight")
        val plan = fire(arclight, sure(arclight, base = 40))
        val levels = mapOf("attack" to Level(100, 100), "strength" to Level(100, 100), "defence" to Level(100, 100))
        assertEquals(mapOf("strength" to 94, "attack" to 94, "defence" to 94), after(arclight, plan, listOf(0), TargetState(levels, isPlayer = false)).targetLevels)
        assertEquals(mapOf("strength" to 89, "attack" to 89, "defence" to 89), after(arclight, plan, listOf(0), TargetState(levels, isPlayer = false, isDemon = true)).targetLevels)
        assertEquals(emptyMap(), after(arclight, missed(plan), listOf(0), TargetState(levels, isPlayer = false)).targetLevels)
    }

    @Test
    fun `the whip moves 10 percent of a player's run energy, never an NPC's`() {
        val whip = def("abyssal_whip")
        val plan = fire(whip, sure(whip, base = 40))
        val transfer = after(whip, plan, listOf(5), TargetState(emptyMap(), isPlayer = true, runEnergy = 10_000), selfRun = 5_000).runEnergy!!
        assertEquals(RunEnergyTransfer(1_000, 6_000, 9_000, "You feel drained!"), transfer)
        assertNull(after(whip, plan, listOf(5), TargetState(emptyMap(), isPlayer = false, runEnergy = 10_000)).runEnergy)
    }

    @Test
    fun `the saradomin sword adds a 1 to 16 magic hit with 2 xp per damage when the melee hit lands`() {
        val ss = def("saradomin_sword")
        val plan = fire(ss, sure(ss, base = 40))
        assertEquals(44, plan.hits.single().maxHit)
        assertEquals(1, after(ss, plan, listOf(10), roll = 0.0).extraMagicHit)
        val top = after(ss, plan, listOf(10), roll = 0.999)
        assertEquals(16, top.extraMagicHit)
        assertEquals(32.0, top.magicXp)
        assertNull(after(ss, missed(plan), listOf(0), roll = 0.5).extraMagicHit)
    }

    @Test
    fun `the halberd's second hit needs a target larger than 1x1`() {
        val halberd = def("dragon_halberd")
        assertEquals(1, fire(halberd, sure(halberd, base = 40, size = 1)).hits.size)
        assertEquals(listOf(44, 44), fire(halberd, sure(halberd, base = 40, size = 2)).hits.map { it.maxHit })
    }

    @Test
    fun `axe, pickaxe and excalibur boost the skill once, capped at base plus the boost`() {
        listOf("dragon_axe" to "woodcutting", "dragon_pickaxe" to "mining").forEach { (item, skill) ->
            val d = def(item)
            assertEquals(Trigger.OnBarClick, d.trigger)
            assertEquals(mapOf(skill to 73), fire(d, SpecialState(energy = 100, self = mapOf(skill to Level(70, 70)))).selfLevels)
            assertEquals(mapOf(skill to 73), fire(d, SpecialState(energy = 100, self = mapOf(skill to Level(73, 70)))).selfLevels)
            assertTrue(SpecialRules.decide(d, SpecialState(energy = 99, self = mapOf(skill to Level(70, 70)))) is SpecialDecision.Refused)
        }
        assertEquals(mapOf("defence" to 78), fire(def("excalibur"), SpecialState(energy = 100, self = mapOf("defence" to Level(70, 70)))).selfLevels)
        assertEquals("Smashing!", def("dragon_pickaxe").forceChat)
        assertEquals(def("dragon_axe"), def("infernal_axe"))
    }

    @Test
    fun `the granite maul fires on the bar near a recent target with no attack delay`() {
        val maul = def("granite_maul")
        assertTrue(maul.noAttackDelay)
        assertEquals(40, fire(maul, sure(maul, base = 40)).hits.single().maxHit)
        assertTrue(SpecialRules.canHome(maul.trigger, ticksSinceAttack = 5, adjacent = true))
        assertFalse(SpecialRules.canHome(maul.trigger, ticksSinceAttack = 6, adjacent = true))
        assertFalse(SpecialRules.canHome(maul.trigger, ticksSinceAttack = 1, adjacent = false))
        assertFalse(SpecialRules.canHome(maul.trigger, ticksSinceAttack = null, adjacent = true))
        assertFalse(SpecialRules.canHome(def("dragon_dagger").trigger, ticksSinceAttack = 1, adjacent = true))
    }

    @Test
    fun `energy regenerates 10 percent per tick of the timer, capped at 100`() {
        assertEquals(1000, SpecialRules.regen(990, table.energy))
        assertEquals(500, SpecialRules.regen(400, table.energy))
        assertEquals(1000, SpecialRules.regen(1000, table.energy))
    }

    @Test
    fun `the mace and longsword multipliers`() {
        val mace = def("dragon_mace")
        assertEquals(60, fire(mace, sure(mace, base = 40)).hits.single().maxHit)
        assertEquals(setOf(1.25), SpecialRules.accuracyMultipliers(mace))
        val dls = def("dragon_longsword")
        assertEquals(50, fire(dls, sure(dls, base = 40)).hits.single().maxHit)
    }
}
