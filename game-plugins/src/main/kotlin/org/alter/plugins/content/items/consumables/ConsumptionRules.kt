package org.alter.plugins.content.items.consumables

import org.alter.api.Skills

/**
 * What the rules need to know about the player, copied out on the game thread. [gates] holds the remaining
 * ticks of each kind's gate timer; [attackDelay] the remaining ticks of `ATTACK_DELAY`.
 */
data class ConsumerState(
    val gates: Map<Kind, Int>,
    val attackDelay: Int,
    val base: IntArray,
    val current: IntArray,
    val hasPrayerGear: Boolean,
    val roll: Double = 0.0,
)

sealed interface Decision {
    data class Blocked(val kind: Kind) : Decision

    data class Apply(val plan: ConsumptionPlan) : Decision
}

/** Everything the game thread then does, in order; nothing here touches the player. */
data class ConsumptionPlan(
    val kind: Kind,
    /** Value for the kind's gate timer. */
    val gateTicks: Int,
    /** Ticks added to a pending attack delay; 0 when no attack was pending or the kind adds none. */
    val attackDelayAdd: Int,
    /** New current level per skill that changes (Hitpoints included). */
    val levels: Map<Int, Int>,
    /** The heal amount rolled, for the "It heals some health." message; 0 when the consumable does not heal. */
    val healAmount: Int,
)

/**
 * The tick rules, verified against the OSRS wiki (`docs/roadmap-pillars.md` §3): food, combo food and potions
 * each have their own gate; food and combo food add their attack delay only to an attack delay that is already
 * running; potions add none. Pure, so the invariants are unit-tested without a world.
 */
object ConsumptionRules {
    fun decide(consumable: Consumable, state: ConsumerState): Decision {
        val kind = consumable.kind
        if ((state.gates[kind] ?: 0) > 0) return Decision.Blocked(kind)

        val levels = LinkedHashMap<Int, Int>()
        fun current(skill: Int) = levels[skill] ?: state.current[skill]

        var healAmount = 0
        consumable.heal?.let { heal ->
            val baseHp = state.base[Skills.HITPOINTS]
            healAmount = heal.amount(baseHp, state.roll)
            val cur = current(Skills.HITPOINTS)
            val cap = if (heal.overheal) baseHp + healAmount else baseHp
            val next = minOf(cur + healAmount, maxOf(cap, cur))
            if (next != cur) levels[Skills.HITPOINTS] = next
        }
        for (effect in consumable.effects) {
            when (effect) {
                is Effect.Boost -> effect.skills.forEach { skill ->
                    val amount = effect.amount(state.base[skill])
                    val cur = current(skill)
                    val next = minOf(cur + amount, maxOf(state.base[skill] + amount, cur))
                    if (next != cur) levels[skill] = next
                }
                is Effect.Drain -> effect.skills.forEach { skill ->
                    val cur = current(skill)
                    val next = maxOf(0, cur - effect.amount(cur))
                    if (next != cur) levels[skill] = next
                }
                is Effect.Restore -> {
                    val skills = when (val t = effect.target) {
                        is Effect.Restore.Target.Skills -> t.skills
                        Effect.Restore.Target.AllLoweredExceptHitpoints -> state.base.indices.filter { it != Skills.HITPOINTS }
                    }
                    skills.forEach { skill ->
                        val cur = current(skill)
                        val base = state.base[skill]
                        if (cur >= base) return@forEach
                        val percent = if (skill == Skills.PRAYER && state.hasPrayerGear && effect.prayerGearPercent != null) effect.prayerGearPercent else effect.percentOfBase
                        levels[skill] = minOf(base, cur + effect.amount(base, percent))
                    }
                }
            }
        }
        val attackDelayAdd = if (state.attackDelay > 0) consumable.attackDelayTicks else 0
        return Decision.Apply(ConsumptionPlan(kind, consumable.delayTicks, attackDelayAdd, levels, healAmount))
    }
}

/**
 * Boosted and drained stats return to base one level at a time (OSRS: one per minute, 90 seconds with Preserve
 * for boosted stats). [step] moves one level toward base; [shouldStep] says whether this 50-tick beat is one
 * where a change happens.
 */
object Normalisation {
    const val BEAT_TICKS = 50

    fun step(base: Int, current: Int): Int = when {
        current > base -> current - 1
        current < base -> current + 1
        else -> current
    }

    /** Every other beat normally (100 ticks); every third beat for a boosted stat under Preserve (150 ticks). */
    fun shouldStep(beat: Int, boosted: Boolean, preserve: Boolean): Boolean =
        if (boosted && preserve) beat % 3 == 0 else beat % 2 == 0
}
