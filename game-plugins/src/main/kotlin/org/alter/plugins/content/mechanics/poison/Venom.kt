package org.alter.plugins.content.mechanics.poison

/**
 * Venom as plain rules, so they can be tested without a world: the hit sequence, what a cure does to it, and how
 * it meets poison. [Poison] and the poison plugin apply them to a pawn.
 *
 * - https://oldschool.runescape.wiki/w/Venom: starts at 6 damage, +2 per hit, capped at 20, every 18 seconds;
 *   venom and poison share one timer and exclude each other; an antipoison turns venom into poison starting at the
 *   venom's damage, and a second dose cures it; NPCs can be envenomed.
 * - https://oldschool.runescape.wiki/w/Poison: "once every 30 game ticks (18 seconds)"; venom is converted to
 *   poison by a means of curing poison.
 */
object Venom {
    const val START_DAMAGE = 6
    const val STEP = 2
    const val CAP = 20

    /** 18 seconds; the tick count is the Poison page's "30 game ticks". */
    const val INTERVAL_TICKS = 30

    /** The damage of the hit after one of [damage]. */
    fun next(damage: Int): Int = minOf(damage + STEP, CAP)

    /** The first [hits] venom hits. */
    fun sequence(hits: Int): List<Int> = generateSequence(START_DAMAGE, ::next).take(hits).toList()

    /**
     * What a dose that cures poison does to a pawn with [venomDamage] (null when not envenomed): a cure that also
     * cures venom ends it; any other poison cure turns venom into poison starting at the venom's damage.
     */
    fun onPoisonCure(venomDamage: Int?, curesVenom: Boolean): CureOutcome = when {
        venomDamage == null -> CureOutcome.None
        curesVenom -> CureOutcome.Cured
        else -> CureOutcome.Downgraded(venomDamage)
    }

    /**
     * The status after [inflicted] (venom unless false: poison) lands on a pawn whose status is [current]. Venom
     * replaces poison; poison never replaces venom. A venom already running is left as it is (TODO: whether a
     * second envenoming restarts the sequence is not on the wiki).
     */
    fun onInflict(current: Status, venom: Boolean = true): Status = when {
        current is Status.Envenomed -> current
        venom -> Status.Envenomed(START_DAMAGE)
        else -> Status.Poisoned
    }

    sealed interface Status {
        object None : Status

        object Poisoned : Status

        data class Envenomed(val damage: Int) : Status
    }

    sealed interface CureOutcome {
        /** No venom to act on: the cure works on poison as usual. */
        object None : CureOutcome

        object Cured : CureOutcome

        /** Venom became poison starting at [poisonInitialDamage]. */
        data class Downgraded(val poisonInitialDamage: Int) : CureOutcome
    }
}
