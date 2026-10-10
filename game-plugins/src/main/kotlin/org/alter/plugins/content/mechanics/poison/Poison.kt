package org.alter.plugins.content.mechanics.poison

import org.alter.api.EquipmentType
import org.alter.api.ext.hasEquipped
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.POISON_TIMER
import org.alter.game.model.timer.TimerKey

/**
 * @author Tom <rspsmods@gmail.com>
 */
object Poison {
    private const val HP_ORB_VARP = 102

    /** Poison immunity from an antipoison potion; set by the consumables plugin, checked by [isImmune]. */
    val IMMUNITY_TIMER = TimerKey()

    fun getDamageForTicks(ticks: Int) = (ticks / 5) + 1

    fun isImmune(pawn: Pawn): Boolean =
        pawn.timers.has(IMMUNITY_TIMER) ||
            when (pawn) {
                is Player -> pawn.hasEquipped(EquipmentType.HEAD, "item.serpentine_helm__228", "item.tanzanite_helm", "item.magma_helm")
                is Npc -> pawn.combatDef.immunePoison
                else -> false
            }

    /** Ends an active poison: what the poison timer does on its own once the ticks run out. */
    fun cure(pawn: Pawn) {
        pawn.attr[POISON_TICKS_LEFT_ATTR] = 0
        pawn.timers.remove(POISON_TIMER)
        if (pawn is Player) setHpOrb(pawn, OrbState.NONE)
    }

    /** The damage of the next venom hit; present only while the pawn is envenomed. */
    val VENOM_DAMAGE_ATTR = AttributeKey<Int>(persistenceKey = "venom_damage", resetOnDeath = true)

    /** Fires each venom hit ([Venom.INTERVAL_TICKS] apart); handled by the poison plugin. */
    val VENOM_TIMER = TimerKey()

    /** Venom immunity from an anti-venom potion; set by the consumables plugin, checked by `Pawn.venom`. */
    val VENOM_IMMUNITY_TIMER = TimerKey()

    /** The pawn's poison or venom status, for [Venom]'s rules. */
    fun status(pawn: Pawn): Venom.Status {
        val venom = pawn.attr[VENOM_DAMAGE_ATTR]
        return when {
            venom != null -> Venom.Status.Envenomed(venom)
            (pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0) > 0 -> Venom.Status.Poisoned
            else -> Venom.Status.None
        }
    }

    /** Ends an active venom. */
    fun cureVenom(pawn: Pawn) {
        pawn.attr.remove(VENOM_DAMAGE_ATTR)
        pawn.timers.remove(VENOM_TIMER)
        if (pawn is Player) setHpOrb(pawn, OrbState.NONE)
    }

    /** Venom turned into poison starting at the venom's damage (an antipoison on venom; wiki Venom page). */
    fun downgradeVenom(pawn: Pawn) {
        val damage = pawn.attr[VENOM_DAMAGE_ATTR] ?: return
        cureVenom(pawn)
        poison(pawn, damage)
        if (pawn is Player) setHpOrb(pawn, OrbState.POISON)
    }

    fun poison(
        pawn: Pawn,
        initialDamage: Int,
    ): Boolean {
        val ticks = (initialDamage * 5) - 4
        val oldDamage = getDamageForTicks(pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0)
        val newDamage = getDamageForTicks(ticks)
        if (oldDamage > newDamage) {
            return false
        }
        pawn.timers[POISON_TIMER] = 1
        pawn.attr[POISON_TICKS_LEFT_ATTR] = ticks
        return true
    }

    fun setHpOrb(
        player: Player,
        state: OrbState,
    ) {
        val value =
            when (state) {
                OrbState.NONE -> 0
                OrbState.POISON -> 1
                OrbState.VENOM -> 1_000_000
            }
        player.setVarp(HP_ORB_VARP, value)
    }

    enum class OrbState {
        NONE,
        POISON,
        VENOM,
    }
}
