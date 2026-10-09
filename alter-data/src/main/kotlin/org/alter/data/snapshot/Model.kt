package org.alter.data.snapshot

import com.fasterxml.jackson.annotation.JsonInclude

/*
 * The committed wiki snapshot (`data/cfg/wiki/`), schema version [SNAPSHOT_SCHEMA_VERSION].
 *
 * Normalized: ids are resolved against our cache, rarities are exact fractions `[n, d]`, quantities are
 * min/max, noted is explicit. The boot loader parses no strings. Bump the schema version only for
 * breaking shape changes; adding optional fields is not breaking.
 *
 * `name` fields hold RSCM names for human review in diffs; the loader ignores them.
 */

const val SNAPSHOT_SCHEMA_VERSION = 1

/** Omits `rolls` when it is the default of 1. */
class DefaultRollsFilter {
    override fun equals(other: Any?) = other == 1

    override fun hashCode() = 1
}

data class Manifest(
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val generator: String,
    /** The cache revision ids were validated against. */
    val cacheRevision: Int,
    val sources: List<String>,
    val rowCounts: Map<String, Int>,
    /** Snapshot-relative path to sha256 of its LF-normalized content. */
    val files: Map<String, String>,
)

// ---- NPCs: npcs/<page-slug>.json ----

data class NpcPage(val page: String, val versions: List<NpcEntry>)

data class NpcEntry(
    /** Wiki `page_name_sub`, e.g. "Abyssal demon#Standard". */
    val source: String,
    val version: String? = null,
    val ids: List<Int>,
    val names: List<String> = emptyList(),
    val combatLevel: Int? = null,
    val hitpoints: Int? = null,
    val attackSpeed: Int? = null,
    val respawnTicks: Int? = null,
    val size: Int? = null,
    val levels: NpcLevels? = null,
    val bonuses: NpcBonuses? = null,
    /** Raw wiki max hits, e.g. "25 (Melee)". Not used by combat yet (it derives max hits from stats). */
    val maxHits: List<String> = emptyList(),
    val attackStyles: List<String> = emptyList(),
    val aggressive: Boolean? = null,
    val poisonous: Boolean? = null,
    val immunities: NpcImmunities = NpcImmunities(),
    /** Monster attributes, e.g. demon, dragon, undead. */
    val attributes: List<String> = emptyList(),
    val slayer: NpcSlayer? = null,
    val elementalWeakness: ElementalWeakness? = null,
)

data class NpcLevels(val attack: Int, val strength: Int, val defence: Int, val ranged: Int, val magic: Int)

data class NpcBonuses(
    val stabAttack: Int = 0,
    val slashAttack: Int = 0,
    val crushAttack: Int = 0,
    val magicAttack: Int = 0,
    val rangedAttack: Int = 0,
    val stabDefence: Int = 0,
    val slashDefence: Int = 0,
    val crushDefence: Int = 0,
    val magicDefence: Int = 0,
    /** Standard ranged defence where the wiki splits light/standard/heavy. */
    val rangedDefence: Int = 0,
    val lightRangedDefence: Int? = null,
    val heavyRangedDefence: Int? = null,
    val attackBonus: Int = 0,
    val strengthBonus: Int = 0,
    val rangedStrength: Int = 0,
    val magicDamage: Int = 0,
)

data class NpcImmunities(
    val poison: Boolean = false,
    val venom: Boolean = false,
    val cannon: Boolean = false,
    val thrall: Boolean = false,
    val burn: String? = null,
    val freeze: String? = null,
)

data class NpcSlayer(val level: Int? = null, val xp: Double? = null, val categories: List<String> = emptyList(), val assignedBy: List<String> = emptyList())

data class ElementalWeakness(val element: String, val percent: Int? = null)

// ---- Items: items.json ----

data class ItemsFile(val items: List<ItemEntry>)

data class ItemEntry(
    val id: Int,
    val name: String? = null,
    val page: String,
    val slot: String? = null,
    val bonuses: ItemBonuses,
    val attackSpeed: Int? = null,
    /** Base attack range in tiles (long range adds 2 in combat). */
    val attackRange: Int? = null,
    val combatStyle: String? = null,
)

data class ItemBonuses(
    val stabAttack: Int = 0,
    val slashAttack: Int = 0,
    val crushAttack: Int = 0,
    val magicAttack: Int = 0,
    val rangedAttack: Int = 0,
    val stabDefence: Int = 0,
    val slashDefence: Int = 0,
    val crushDefence: Int = 0,
    val magicDefence: Int = 0,
    val rangedDefence: Int = 0,
    val meleeStrength: Int = 0,
    val rangedStrength: Int = 0,
    /** Percent, may be fractional (e.g. 2.5). */
    val magicDamage: Double = 0.0,
    val prayer: Int = 0,
)

// ---- Drops: drops/<page-slug>.json ----

data class DropPage(val page: String, val tables: List<DropTable>)

data class DropTable(
    /** Wiki `page_name_sub` the lines came from, e.g. "Abyssal demon#Standard". */
    val source: String,
    val npcIds: List<Int>,
    /**
     * True when the main-table lines add up to more than 1. They are then all emitted as independent
     * tertiary rolls so every item keeps its per-kill wiki rate; an override can restore the real structure.
     */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val needsReview: Boolean = false,
    val always: List<Drop> = emptyList(),
    val main: List<Drop> = emptyList(),
    val tertiary: List<Drop> = emptyList(),
)

data class Drop(
    val item: Int,
    val name: String? = null,
    val min: Int,
    val max: Int,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val noted: Boolean = false,
    /** `[n, d]`; absent for always-drops. */
    val chance: List<Long>? = null,
    /** How many times this line is rolled per kill (wiki "Rolls"). */
    @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = DefaultRollsFilter::class)
    val rolls: Int = 1,
    /** The wiki's own rarity text, for review only. */
    val wiki: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val approx: Boolean = false,
)
