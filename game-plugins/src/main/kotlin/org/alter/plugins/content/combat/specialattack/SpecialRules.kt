package org.alter.plugins.content.combat.specialattack

import kotlin.math.floor

/**
 * The special attack rules as pure functions: the plugin copies the state out of the world, these decide, and the
 * plugin applies the result. No world, cache or player is needed, so every rule is unit-tested.
 */

data class Level(val current: Int, val base: Int)

/**
 * Everything [SpecialRules.decide] needs. [energy] is in percent; [accuracy] maps each distinct accuracy multiplier
 * of the special to the hit chance the combat formula gives with it; [rolls] has one uniform `[0, 1)` roll per hit.
 */
data class SpecialState(
    val energy: Int,
    val vsNpc: Boolean = true,
    val targetSize: Int = 1,
    val baseMaxHit: Int = 0,
    val missingPrayer: Int = 0,
    val accuracy: Map<Double, Double> = emptyMap(),
    val rolls: List<Double> = emptyList(),
    val self: Map<String, Level> = emptyMap(),
)

enum class RefusalReason { NOT_ENOUGH_ENERGY }

sealed interface SpecialDecision {
    data class Refused(val reason: RefusalReason) : SpecialDecision

    data class Fire(val plan: SpecialPlan) : SpecialDecision
}

data class PlannedHit(val maxHit: Int, val landed: Boolean, val delay: Int)

/** [selfLevels] are the attacker's new current levels from boosts applied when the special fires. */
data class SpecialPlan(val energyAfter: Int, val hits: List<PlannedHit>, val selfLevels: Map<String, Int>) {
    val landed: Boolean get() = hits.any { it.landed }
}

/** The target as the hit lands; [levels] holds only the skills it has (NPCs have no Prayer). [runEnergy] in engine units (0..10000). */
data class TargetState(val levels: Map<String, Level>, val isPlayer: Boolean, val isDemon: Boolean = false, val runEnergy: Int = 0)

/** What [SpecialRules.afterRoll] needs besides the plan: the attacker and target now, and a roll for random extras. */
data class AfterRollState(val self: Map<String, Level>, val selfRunEnergy: Int, val target: TargetState, val roll: Double)

/** Damage-dependent results; null or empty means nothing to apply. */
data class SpecialOutcome(
    val selfLevels: Map<String, Int> = emptyMap(),
    val targetLevels: Map<String, Int> = emptyMap(),
    val freezeTicks: Int? = null,
    val runEnergy: RunEnergyTransfer? = null,
    val extraMagicHit: Int? = null,
    val magicXp: Double = 0.0,
)

/** New run energy of both sides in engine units, and the line the target sees. */
data class RunEnergyTransfer(val amount: Int, val selfAfter: Int, val targetAfter: Int, val targetMessage: String?)

object SpecialRules {
    /** Varp 300 (`sa_energy`) holds the energy as percent x 10. */
    const val VARP_UNITS_PER_PERCENT = 10

    /** The engine's run energy scale: 10000 is 100%. */
    const val RUN_ENERGY_MAX_UNITS = 10_000

    /** A melee hit lands on the next tick, as in [org.alter.plugins.content.combat.strategy.MeleeCombatStrategy]. */
    const val HIT_DELAY = 1

    /** Guards the floors against binary error, e.g. 40 x 1.15 must be 46, not 45.999... */
    private const val EPSILON = 1e-9

    fun floorMul(value: Int, multiplier: Double): Int = floor(value * multiplier + EPSILON).toInt()

    /** Distinct accuracy multipliers the plugin must precompute hit chances for. */
    fun accuracyMultipliers(def: SpecialDef): Set<Double> = def.hits.map { it.accuracy }.toSet()

    /** The max hit of [hit] from the unmodified [base]: the missing-prayer bonus, then each multiplier with a floor after each. */
    fun maxHit(hit: HitSpec, base: Int, missingPrayer: Int): Int {
        var max = base
        if (hit.damageBonusPerMissingPrayerPoint > 0) {
            max = floorMul(max, 1.0 + missingPrayer * hit.damageBonusPerMissingPrayerPoint / 100.0)
        }
        hit.damage.forEach { max = floorMul(max, it) }
        return max
    }

    /** `cur + plus`, never above `base + plus`, and never lowering a level that is already higher. */
    fun boost(current: Int, base: Int, plus: Int): Int = minOf(current + plus, maxOf(base + plus, current))

    /** The energy varp after one regen tick, capped at the maximum. */
    fun regen(units: Int, energy: EnergySettings): Int =
        minOf(energy.max * VARP_UNITS_PER_PERCENT, units + energy.regenPercent * VARP_UNITS_PER_PERCENT)

    /** Whether a bar click fires the special at once at the last target. */
    fun canHome(trigger: Trigger, ticksSinceAttack: Int?, adjacent: Boolean): Boolean =
        trigger is Trigger.OnBarClickNearTarget && ticksSinceAttack != null && ticksSinceAttack <= trigger.homingTicks && adjacent

    fun decide(def: SpecialDef, state: SpecialState): SpecialDecision {
        if (state.energy < def.energy) return SpecialDecision.Refused(RefusalReason.NOT_ENOUGH_ENERGY)
        val hits = def.hits.mapIndexedNotNull { i, h ->
            if (h.onlyIfTargetLargerThan1x1 && state.targetSize <= 1) return@mapIndexedNotNull null
            val chance = state.accuracy[h.accuracy] ?: error("no hit chance for accuracy x${h.accuracy}")
            val roll = state.rolls[if (def.sharedRoll) 0 else i]
            PlannedHit(
                maxHit = maxHit(h, state.baseMaxHit, state.missingPrayer),
                landed = chance >= roll,
                delay = HIT_DELAY + if (state.vsNpc) h.npcDelayExtra else 0,
            )
        }
        val selfLevels = def.effects.filterIsInstance<SpecialEffect.BoostSelf>().associate { b ->
            val level = state.self[b.skill] ?: error("no level for ${b.skill}")
            b.skill to boost(level.current, level.base, b.plus)
        }
        return SpecialDecision.Fire(SpecialPlan(state.energy - def.energy, hits, selfLevels))
    }

    /** Hitpoints and Prayer restored for [damage]: a percent of it rounded up, at least the minimum. */
    fun heal(effect: SpecialEffect.HealSelf, damage: Int): Pair<Int, Int> =
        maxOf(effect.minHitpoints, ceilPercent(damage, effect.hitpointsPercentOfDamage)) to
            maxOf(effect.minPrayer, ceilPercent(damage, effect.prayerPercentOfDamage))

    private fun ceilPercent(value: Int, percent: Int) = (value * percent + 99) / 100

    /** The effects that depend on the outcome of the hits; [damages] are the rolled damage of each planned hit. */
    fun afterRoll(def: SpecialDef, plan: SpecialPlan, damages: List<Int>, state: AfterRollState): SpecialOutcome {
        val total = damages.sum()
        fun applies(on: EffectCondition) = when (on) {
            EffectCondition.HIT -> plan.landed
            EffectCondition.DAMAGE -> total > 0
            EffectCondition.ALWAYS -> true
        }
        val self = mutableMapOf<String, Int>()
        val target = mutableMapOf<String, Int>()
        fun targetCurrent(skill: String) = target[skill] ?: state.target.levels[skill]?.current
        var outcome = SpecialOutcome()
        def.effects.filter { it !is SpecialEffect.BoostSelf && applies(it.on) }.forEach { effect ->
            when (effect) {
                is SpecialEffect.HealSelf -> {
                    val (hp, prayer) = heal(effect, total)
                    listOf("hitpoints" to hp, "prayer" to prayer).forEach { (skill, amount) ->
                        val level = state.self[skill] ?: return@forEach
                        self[skill] = minOf(level.current + amount, maxOf(level.base, level.current))
                    }
                }
                is SpecialEffect.DrainTargetSkill -> targetCurrent(effect.skill)?.let { cur ->
                    target[effect.skill] = cur - cur * effect.percentOfCurrent / 100
                }
                is SpecialEffect.DrainTargetByDamage -> {
                    var remaining = total
                    effect.order.forEach { skill ->
                        val cur = targetCurrent(skill) ?: return@forEach
                        val drained = minOf(cur, remaining)
                        if (drained > 0) target[skill] = cur - drained
                        remaining -= drained
                    }
                }
                is SpecialEffect.DrainTargetSkills -> effect.skills.forEach { skill ->
                    val level = state.target.levels[skill] ?: return@forEach
                    val percent = if (state.target.isDemon) effect.demonPercentOfBase else effect.percentOfBase
                    target[skill] = maxOf(0, (targetCurrent(skill) ?: level.current) - (effect.plus + level.base * percent / 100))
                }
                is SpecialEffect.FreezeTarget -> outcome = outcome.copy(freezeTicks = effect.ticks)
                is SpecialEffect.TransferRunEnergy -> if (state.target.isPlayer || !effect.pvpOnly) {
                    val amount = state.target.runEnergy * effect.percent / 100
                    outcome = outcome.copy(
                        runEnergy = RunEnergyTransfer(amount, minOf(RUN_ENERGY_MAX_UNITS, state.selfRunEnergy + amount), state.target.runEnergy - amount, effect.targetMessage),
                    )
                }
                is SpecialEffect.ExtraMagicHit -> {
                    val span = effect.max - effect.min
                    val damage = effect.min + (state.roll * (span + 1)).toInt().coerceIn(0, span)
                    outcome = outcome.copy(extraMagicHit = damage, magicXp = damage * effect.magicXpPerDamage)
                }
                is SpecialEffect.BoostSelf -> {}
            }
        }
        return outcome.copy(selfLevels = self, targetLevels = target)
    }
}
