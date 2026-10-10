package org.alter.plugins.content.combat.autocast

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.getSpellbook
import org.alter.api.ext.getVarbit
import org.alter.api.ext.setVarbit
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.strategy.magic.CombatSpell

/**
 * The one place combat reads and writes a player's autocast selection. The selection lives in varbit 276
 * (`autocast_spell`, an autocast id; 0 = none) and the defensive flag in varbit 2668 (`autocast_defmode`), the
 * variables the 241 client reads (data/reports/autocast-241-spike.md). The per-group memory is a saved attribute.
 */
object Autocast {
    /** "group=SPELL;..." (see [AutocastRules.encode]); kept through death, as on the wiki since July 2026. */
    val MEMORY_ATTR = AttributeKey<String>(persistenceKey = "autocast_memory")

    /** Runs for `pvpSwapLockTicks` after attacking a player in a PvP area; equipping a staff meanwhile clears the selection. */
    val PVP_SWAP_LOCK = TimerKey()

    /** Set by [AutocastPlugin] at init. */
    @Volatile
    lateinit var service: AutocastService

    fun selected(player: Player): Int = player.getVarbit(Combat.SELECTED_AUTOCAST_VARBIT)

    fun spell(player: Player): CombatSpell? {
        val id = selected(player)
        return if (id == AutocastRules.NONE) null else CombatSpell.values.firstOrNull { it.autoCastId == id }
    }

    fun isDefensive(player: Player): Boolean = selected(player) != AutocastRules.NONE && player.getVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT) != 0

    /** Selects [id]; clearing it (0) also clears the defensive flag. */
    fun select(player: Player, id: Int, defensive: Boolean? = null) {
        player.setVarbit(Combat.SELECTED_AUTOCAST_VARBIT, id)
        if (id == AutocastRules.NONE) {
            player.setVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT, 0)
        } else if (defensive != null) {
            player.setVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT, if (defensive) 1 else 0)
        }
    }

    /** Starts the PvP swap lock; a no-op before the service has loaded. */
    fun startPvpSwapLock(player: Player) {
        if (!::service.isInitialized) return
        val ticks = service.table.pvpSwapLockTicks
        if (ticks > 0) player.timers[PVP_SWAP_LOCK] = ticks
    }

    fun memory(player: Player): Map<String, Int> = AutocastRules.decode(player.attr[MEMORY_ATTR], service.table.groups)

    fun setMemory(player: Player, memory: Map<String, Int>) {
        player.attr[MEMORY_ATTR] = AutocastRules.encode(memory, service.table.groups)
    }

    /** The loaded groups the equipped weapon may autocast. */
    fun allowedGroups(player: Player): List<SpellGroup> {
        val weapon = player.getEquipment(EquipmentType.WEAPON) ?: return emptyList()
        return service.groupsFor(weapon.id, maxOf(0, getItem(weapon.id).weaponType))
    }

    /** The group matching the player's spellbook, by the group's `spellbook` name. */
    fun spellbookGroup(player: Player, groups: List<SpellGroup>): SpellGroup? {
        val book = player.getSpellbook().name.lowercase()
        return groups.firstOrNull { it.spellbook == book }
    }

    /** Re-applies [AutocastRules.onWeaponChange] after a weapon change or on login. */
    fun onWeaponChange(player: Player, equipping: Boolean) {
        if (!::service.isInitialized) return
        val groups = allowedGroups(player)
        val current = selected(player)
        val next = AutocastRules.onWeaponChange(
            current = current,
            allowed = groups,
            memory = memory(player),
            equipping = equipping,
            pvpLocked = player.timers.has(PVP_SWAP_LOCK),
            preferredGroup = spellbookGroup(player, groups)?.name,
        )
        if (next != current) select(player, next)
    }
}
