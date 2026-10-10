package org.alter.plugins.content.mechanics.poison

import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player

/**
 * @author Tom <rspsmods@gmail.com>
 */

fun Pawn.poison(
    initialDamage: Int,
    onPoison: () -> Unit,
) {
    // Poison never replaces venom (they share one timer; wiki Venom page).
    if (Venom.onInflict(Poison.status(this), venom = false) is Venom.Status.Envenomed) return
    if (!Poison.isImmune(this) && Poison.poison(this, initialDamage)) {
        if (this is Player) {
            Poison.setHpOrb(this, Poison.OrbState.POISON)
        }
        onPoison()
    }
}

/**
 * Envenoms the pawn: venom replaces an active poison and hits from [Venom.START_DAMAGE] on the poison plugin's
 * venom timer. Refused while immune: poison immunity (antipoison, serpentine helm, an NPC's poison immunity), an
 * anti-venom's venom immunity, or an NPC whose combat definition is immune to venom. NPCs can be envenomed.
 */
fun Pawn.venom(onVenom: () -> Unit) {
    if (Poison.isImmune(this) || timers.has(Poison.VENOM_IMMUNITY_TIMER) || (this as? Npc)?.combatDef?.immuneVenom == true) return
    val current = Poison.status(this)
    val next = Venom.onInflict(current)
    if (next == current) return
    Poison.cure(this)
    attr[Poison.VENOM_DAMAGE_ATTR] = Venom.START_DAMAGE
    timers[Poison.VENOM_TIMER] = 1
    if (this is Player) {
        Poison.setHpOrb(this, Poison.OrbState.VENOM)
    }
    onVenom()
}
