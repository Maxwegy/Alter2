package org.alter.plugins.content.combat.specialattack

import kotlinx.coroutines.launch
import org.alter.api.EquipmentType
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setVarp
import org.alter.api.ext.toggleVarp
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.LAST_HIT_ATTR
import org.alter.game.model.attr.NEW_ACCOUNT_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.service.GameService
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.infrastructure.InfrastructureService
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.interfaces.attack.AttackTab.ATTACK_TAB_INTERFACE_ID
import org.alter.plugins.content.interfaces.attack.AttackTab.SPECIAL_ATTACK_VARP

/**
 * The special attack bar and energy, driven by `data/cfg/combat/special_attacks.json`: regen on a timer, the
 * combat-tab bar and the minimap orb, the bar switching off on a weapon change and logout, and `::reloadspecials`.
 * The specials themselves fire through [SpecialAttacks.execute]; this plugin carries no numbers or chat lines.
 */
class SpecialAttacksPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val service = SpecialAttacksService()

    init {
        loadService(service)
        SpecialAttacks.service = service

        onLogin {
            if (player.attr.getOrDefault(NEW_ACCOUNT_ATTR, false)) {
                AttackTab.setEnergy(player, service.energy.max)
            }
            if (service.energy.resetTimerOnLogin || !player.timers.has(AttackTab.SPEC_RESTORE)) {
                AttackTab.resetRestorationTimer(player, service.energy.regenIntervalTicks)
            }
        }

        onTimer(AttackTab.SPEC_RESTORE) {
            AttackTab.restoreEnergy(player, service.energy)
            AttackTab.resetRestorationTimer(player, service.energy.regenIntervalTicks)
        }

        // The bar on the combat tab and the minimap orb.
        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 36) { onBar(player) }
        onButton(interfaceId = ORBS_INTERFACE_ID, component = 35) { onBar(player) }

        onEquipToSlot(EquipmentType.WEAPON.id) {
            player.setVarp(SPECIAL_ATTACK_VARP, 0)
        }

        onLogout {
            player.setVarp(SPECIAL_ATTACK_VARP, 0)
        }

        onCommand("reloadspecials", Privilege.DEV_POWER, description = "Re-read data/cfg/combat/special_attacks.json") {
            val infra = world.getService(InfrastructureService::class.java) ?: return@onCommand player.message("Data infrastructure is not running.")
            val game = world.getService(GameService::class.java) ?: return@onCommand player.message("No game service.")
            infra.io.scope.launch {
                val result = runCatching { service.load() }
                game.submitGameThreadJob {
                    result.onSuccess { next ->
                        service.swap(next)
                        player.message("Reloaded special attacks: ${service.loadedCount} loaded, ${service.skippedCount} skipped (todo).")
                    }.onFailure { player.message("Reload failed: ${it.message}") }
                }
            }
        }
    }

    private fun onBar(player: Player) {
        val def = SpecialAttacks.lookup(player)
        when (val trigger = def?.trigger) {
            Trigger.OnBarClick -> SpecialAttacks.execute(player, null, world)
            is Trigger.OnBarClickNearTarget -> if (!fireAtRecentTarget(player, trigger)) player.toggleVarp(SPECIAL_ATTACK_VARP)
            else -> player.toggleVarp(SPECIAL_ATTACK_VARP)
        }
    }

    /** The granite maul: fire at once at the last target when it was attacked recently and is adjacent. */
    private fun fireAtRecentTarget(player: Player, trigger: Trigger.OnBarClickNearTarget): Boolean {
        val target = player.attr[LAST_HIT_ATTR]?.get() ?: return false
        val ticksSince = player.attr[Combat.LAST_ATTACK_CYCLE]?.let { world.currentCycle - it }
        val adjacent = Combat.areBordering(
            player.tile.x, player.tile.z, player.getSize(), player.getSize(),
            target.tile.x, target.tile.z, target.getSize(), target.getSize(),
        )
        if (!SpecialRules.canHome(trigger, ticksSince, adjacent) || !Combat.canEngage(player, target)) return false
        if (!SpecialAttacks.execute(player, target, world)) return false
        AttackTab.disableSpecial(player)
        player.facePawn(target)
        return true
    }

    private companion object {
        /** Gameval `orbs`: the minimap orbs, whose component 35 is the special attack orb. */
        const val ORBS_INTERFACE_ID = 160
    }
}
