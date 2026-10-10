package org.alter.plugins.content.combat.autocast

import org.alter.api.EquipmentType
import org.alter.api.InterfaceDestination
import org.alter.api.ext.InterfaceEvent
import org.alter.api.ext.getInteractingSlot
import org.alter.api.ext.openInterface
import org.alter.api.ext.player
import org.alter.api.ext.sendWeaponComponentInformation
import org.alter.api.ext.setInterfaceEvents
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Autocast driven by `data/cfg/combat/autocast.json`: the selection is re-checked against the weapon on every
 * weapon equip and unequip and on login ([AutocastRules.onWeaponChange]). Death changes nothing.
 *
 * The UI, bound only when the file's `ui` block is sourced (data/reports/autocast-241-spike.md): the combat
 * tab's "Choose spell" buttons open interface 201 in the tab with the menu varp set to the group's list; a spell
 * button (child n of the spells layer = autocast id n) selects it, child 0 cancels; both reopen the combat tab.
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

        // The service initialises after all plugins load, so the buttons are bound from the file directly.
        val ui = AutocastDefs.load(service.pathOrDefault()).ui
        if (ui.bound) bindUi(ui)
    }

    private fun bindUi(ui: AutocastUi) {
        val combatTab = ui.combatInterface!!
        val buttons = ui.spellButtons!!
        val menuVarp = ui.menuVarp!!

        onButton(combatTab, ui.chooseSpellButton!!) { openMenu(player, buttons, menuVarp, defensive = false) }
        onButton(combatTab, ui.defensiveButton!!) { openMenu(player, buttons, menuVarp, defensive = true) }
        onButton(buttons.interfaceId, buttons.component) { choose(player, buttons, player.getInteractingSlot()) }
    }

    /** Lists the group of the player's spellbook that the weapon may autocast; nothing happens when there is none. */
    private fun openMenu(player: Player, buttons: SpellButtons, menuVarp: Int, defensive: Boolean) {
        val group = Autocast.spellbookGroup(player, Autocast.allowedGroups(player)) ?: return
        player.attr[MENU_GROUP_ATTR] = group.name
        player.attr[MENU_DEFENSIVE_ATTR] = defensive
        player.setVarp(menuVarp, service.menuValue(group))
        player.openInterface(buttons.interfaceId, InterfaceDestination.ATTACK)
        // The spell buttons are created by client script 2098, so op1 must be enabled for each child.
        player.setInterfaceEvents(buttons.interfaceId, buttons.component, buttons.cancelSlot..buttons.lastSlot, InterfaceEvent.ClickOp1.flag)
    }

    private fun choose(player: Player, buttons: SpellButtons, slot: Int) {
        val groupName = player.attr[MENU_GROUP_ATTR]
        val defensive = player.attr[MENU_DEFENSIVE_ATTR] ?: false
        player.attr.remove(MENU_GROUP_ATTR)
        player.attr.remove(MENU_DEFENSIVE_ATTR)
        if (slot != buttons.cancelSlot && groupName != null) {
            // Re-check against the weapon now worn, limited to the group the menu listed.
            val group = Autocast.allowedGroups(player).filter { it.name == groupName }
            val memory = AutocastRules.onSelect(slot, group, Autocast.memory(player))
            if (memory != null) {
                Autocast.setMemory(player, memory)
                Autocast.select(player, slot, defensive)
            }
        }
        player.openInterface(InterfaceDestination.ATTACK)
        player.sendWeaponComponentInformation()
    }

    private companion object {
        val MENU_GROUP_ATTR = AttributeKey<String>(temp = true)
        val MENU_DEFENSIVE_ATTR = AttributeKey<Boolean>(temp = true)
    }
}
