package org.alter.plugins.content.combat.autocast

import org.alter.api.EquipmentType
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Autocast driven by `data/cfg/combat/autocast.json`: the selection is re-checked against the weapon on every
 * weapon equip and unequip and on login ([AutocastRules.onWeaponChange]). Death changes nothing.
 */
class AutocastPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val service = AutocastService()

    init {
        loadService(service)
        Autocast.service = service

        onEquipToSlot(EquipmentType.WEAPON.id) { Autocast.onWeaponChange(player, equipping = true) }
        onUnequipFromSlot(EquipmentType.WEAPON.id) { Autocast.onWeaponChange(player, equipping = false) }
        onLogin { Autocast.onWeaponChange(player, equipping = false) }
    }
}
