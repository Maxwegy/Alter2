package org.alter.plugins.content.combat.autocast

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import java.nio.file.Files
import java.nio.file.Path

/**
 * The autocast data model: `data/cfg/combat/autocast.json` parsed into spell groups, the weapons that may
 * autocast each group, and the 241 interface ids of the autocast menu (sourced from the cache, see
 * `data/reports/autocast-241-spike.md`). Spells are [CombatSpell] names; the client stores a selection as the
 * spell's autocast id (varbit 276). Item names are RSCM names resolved by [AutocastService].
 */

/**
 * A set of spells that weapons share a remembered selection for. [spells] maps a [CombatSpell] name to its
 * autocast id. [menuKey] is the RSCM item whose id goes in the menu varp to list this group (null sends -1).
 */
data class SpellGroup(
    val name: String,
    val spellbook: String?,
    val menuKey: String?,
    val spells: Map<String, Int>,
    val source: String?,
    val todo: String?,
) {
    val loaded: Boolean get() = todo.isNullOrBlank()

    fun contains(autocastId: Int): Boolean = autocastId in spells.values

    fun nameOf(autocastId: Int): String? = spells.entries.firstOrNull { it.value == autocastId }?.key
}

/** The 201 menu's spell buttons: children of [interfaceId]:[component], child [cancelSlot] cancels, child n is autocast id n. */
data class SpellButtons(val interfaceId: Int, val component: Int, val cancelSlot: Int, val lastSlot: Int)

/** Interface ids of the autocast UI; [bound] only when every id is sourced and there is no todo. */
data class AutocastUi(
    val combatInterface: Int?,
    val chooseSpellButton: Int?,
    val defensiveButton: Int?,
    val spellButtons: SpellButtons?,
    val menuVarp: Int?,
    val source: String?,
    val todo: String?,
) {
    val bound: Boolean
        get() = todo.isNullOrBlank() && combatInterface != null && chooseSpellButton != null && defensiveButton != null &&
            spellButtons != null && menuVarp != null

    companion object {
        val UNBOUND = AutocastUi(null, null, null, null, null, null, "no ui block")
    }
}

data class AutocastTable(
    val groups: List<SpellGroup>,
    /** Weapon type (item param, varbit 357) to group names. */
    val byWeaponType: Map<Int, List<String>>,
    /** RSCM item name to group names. */
    val byItem: Map<String, List<String>>,
    val pvpSwapLockTicks: Int,
    val ui: AutocastUi,
    val todo: List<String> = emptyList(),
) {
    val loadedGroups: List<SpellGroup> get() = groups.filter { it.loaded }

    /** The loaded groups a weapon may autocast, in file order, from its type and its own entry. */
    fun groupsFor(itemName: String?, weaponType: Int): List<SpellGroup> {
        val names = (byWeaponType[weaponType].orEmpty() + itemName?.let { byItem[it] }.orEmpty()).toSet()
        return loadedGroups.filter { it.name in names }
    }

    companion object {
        val EMPTY = AutocastTable(emptyList(), emptyMap(), emptyMap(), 0, AutocastUi.UNBOUND)
    }
}

object AutocastDefs {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val spellIds: Map<String, Int> = CombatSpell.values.associate { it.name to it.autoCastId }

    fun load(file: Path): AutocastTable = parse(Files.readString(file))

    fun parse(json: String): AutocastTable {
        val root: JsonNode = mapper.readTree(json)
        require(root["schemaVersion"]?.asInt() == 1) { "autocast.json: unsupported schemaVersion ${root["schemaVersion"]}" }
        val lock = root["pvpSwapLockTicks"]?.takeIf { it.isInt }?.asInt() ?: throw IllegalArgumentException("autocast.json: pvpSwapLockTicks is missing")
        require(lock > 0) { "autocast.json: pvpSwapLockTicks must be positive" }
        val groups = root["groups"]?.map { group(it) } ?: throw IllegalArgumentException("autocast.json: groups is missing")
        require(groups.map { it.name }.toSet().size == groups.size) { "autocast.json: a group name is used twice" }
        val names = groups.map { it.name }.toSet()
        fun groupNames(owner: String, n: JsonNode?): List<String> {
            val list = n?.map { it.asText() } ?: emptyList()
            require(list.isNotEmpty()) { "autocast.json: $owner has no groups" }
            list.forEach { require(it in names) { "autocast.json: $owner names unknown group '$it'" } }
            return list
        }
        val byWeaponType = root["byWeaponType"]?.fields()?.asSequence()?.associate { (k, v) ->
            val type = k.toIntOrNull() ?: throw IllegalArgumentException("autocast.json: weapon type '$k' is not a number")
            type to groupNames("weapon type $k", v)
        } ?: emptyMap()
        val byItem = LinkedHashMap<String, List<String>>()
        root["byItem"]?.forEach { entry ->
            val items = entry["items"]?.map { it.asText() } ?: emptyList()
            require(items.isNotEmpty()) { "autocast.json: a byItem entry has no items" }
            val g = groupNames(items.first(), entry["groups"])
            items.forEach { require(byItem.put(it, g) == null) { "autocast.json: $it is listed twice" } }
        }
        val todo = root["todo"]?.map { it.asText() } ?: emptyList()
        return AutocastTable(groups, byWeaponType, byItem, lock, ui(root["ui"]), todo)
    }

    private fun text(n: JsonNode?): String? = n?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private fun int(n: JsonNode?): Int? = n?.takeUnless { it.isNull }?.also { require(it.isInt) { "autocast.json: '$it' is not a number" } }?.asInt()

    private fun group(node: JsonNode): SpellGroup {
        val name = text(node["name"]) ?: throw IllegalArgumentException("autocast.json: a group has no name")
        val spells = node["spells"]?.map { it.asText() } ?: emptyList()
        val resolved = spells.associateWith { spellIds[it] ?: throw IllegalArgumentException("autocast.json: $name: '$it' is not a CombatSpell") }
        require(resolved.size == spells.size) { "autocast.json: $name lists a spell twice" }
        val group = SpellGroup(name, text(node["spellbook"]), text(node["menuKey"]), resolved, text(node["source"]), text(node["todo"]))
        if (group.loaded) require(group.spells.isNotEmpty()) { "autocast.json: $name is loaded but has no spells" }
        return group
    }

    private fun ui(node: JsonNode?): AutocastUi {
        if (node == null || node.isNull) return AutocastUi.UNBOUND
        val buttons = node["spellButtons"]?.takeUnless { it.isNull }?.let {
            SpellButtons(
                int(it["interface"]) ?: throw IllegalArgumentException("autocast.json: ui.spellButtons.interface is missing"),
                int(it["component"]) ?: throw IllegalArgumentException("autocast.json: ui.spellButtons.component is missing"),
                int(it["cancelSlot"]) ?: throw IllegalArgumentException("autocast.json: ui.spellButtons.cancelSlot is missing"),
                int(it["lastSlot"]) ?: throw IllegalArgumentException("autocast.json: ui.spellButtons.lastSlot is missing"),
            )
        }
        return AutocastUi(
            combatInterface = int(node["combatInterface"]),
            chooseSpellButton = int(node["chooseSpellButton"]),
            defensiveButton = int(node["defensiveButton"]),
            spellButtons = buttons,
            menuVarp = int(node["menuVarp"]),
            source = text(node["source"]),
            todo = text(node["todo"]),
        )
    }
}
