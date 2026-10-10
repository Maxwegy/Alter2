package org.alter.plugins.content.interfaces.attack

import org.alter.api.ext.getVarp
import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.combat.specialattack.EnergySettings
import org.alter.plugins.content.combat.specialattack.SpecialRules

/**
 * @author Tom <rspsmods@gmail.com>
 * @author Sequential - Special Attack Restore
 */
object AttackTab {
    const val ATTACK_TAB_INTERFACE_ID = 593

    /*
     * Components of 593 (gameval `combat_interface`) in the revision 241 cache; the gameval names and ops are in
     * data/reports/combat-orbs-241-components.md. The 228-era ids were one lower.
     */
    /** The style buttons `0`..`3`; the index is the value of [ATTACK_STYLE_VARP]. */
    val ATTACK_STYLE_COMPONENTS = listOf(6, 10, 14, 18)
    /** `retaliate`, op1 "Auto retaliate". */
    const val AUTO_RETALIATE_COMPONENT = 32
    /** `special_attack`, op1 "Use Special Attack". */
    const val SPECIAL_ATTACK_COMPONENT = 39
    const val ATTACK_STYLE_VARP = 43
    const val DISABLE_AUTO_RETALIATE_VARP = 172
    private const val SPECIAL_ATTACK_ENERGY_VARP = 300
    const val SPECIAL_ATTACK_VARP = 301

    val SPEC_RESTORE = TimerKey()

    fun setEnergy(
        p: Player,
        amount: Int,
    ) {
        check(amount in 0..100)
        p.setVarp(SPECIAL_ATTACK_ENERGY_VARP, amount * 10)
    }

    /** One regen step; the amounts come from `data/cfg/combat/special_attacks.json`. */
    fun restoreEnergy(p: Player, energy: EnergySettings) {
        p.setVarp(SPECIAL_ATTACK_ENERGY_VARP, SpecialRules.regen(p.getVarp(SPECIAL_ATTACK_ENERGY_VARP), energy))
    }

    fun getEnergy(p: Player): Int = p.getVarp(SPECIAL_ATTACK_ENERGY_VARP) / 10

    fun disableSpecial(p: Player) {
        p.setVarp(SPECIAL_ATTACK_VARP, 0)
    }

    fun isSpecialEnabled(p: Player): Boolean = p.getVarp(SPECIAL_ATTACK_VARP) == 1

    fun resetRestorationTimer(player: Player, ticks: Int) = player.timers.set(SPEC_RESTORE, ticks)
}
