package org.alter.data.items

import org.alter.data.cache.CacheView
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import org.alter.data.snapshot.ItemBonuses
import org.alter.data.snapshot.ItemEntry
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.double
import org.alter.data.wiki.ids
import org.alter.data.wiki.int
import org.alter.data.wiki.str

/**
 * Maps `infobox_bonuses` rows (joined to `infobox_item` for ids) to [ItemEntry]s for items in our cache.
 * The cache stays authoritative for stats at runtime; these entries only fill gaps and add the
 * wiki-only fields (attack range, combat style).
 */
class ItemStatsMapper(private val cache: CacheView) {
    fun map(rows: List<BucketRow>, report: ReportBuilder): List<ItemEntry> {
        val byId = sortedMapOf<Int, ItemEntry>()
        rows.sortedBy { it.str("page_name_sub") ?: "" }.forEach { row ->
            val page = row.str("page_name_sub") ?: row.str("page_name") ?: return@forEach
            val ids = row.ids(JOINED_ITEM_ID).filter { cache.hasItem(it) && !cache.isNotedOrPlaceholder(it) }
            if (ids.isEmpty()) {
                report.add(SECTION_NO_IDS, page, Report.Severity.INFO)
                return@forEach
            }
            ids.forEach { id ->
                if (id in byId) {
                    report.add(SECTION_DUPLICATES, "$id: '${byId.getValue(id).page}' and '$page' (kept the first)", Report.Severity.INFO)
                } else {
                    byId[id] = toEntry(id, page, row)
                }
            }
        }
        return byId.values.toList()
    }

    private fun toEntry(id: Int, page: String, row: BucketRow) = ItemEntry(
        id = id,
        name = cache.rscmName("item", id),
        page = page,
        slot = row.str("equipment_slot")?.lowercase(),
        bonuses = ItemBonuses(
            stabAttack = row.int("stab_attack_bonus") ?: 0,
            slashAttack = row.int("slash_attack_bonus") ?: 0,
            crushAttack = row.int("crush_attack_bonus") ?: 0,
            magicAttack = row.int("magic_attack_bonus") ?: 0,
            rangedAttack = row.int("range_attack_bonus") ?: 0,
            stabDefence = row.int("stab_defence_bonus") ?: 0,
            slashDefence = row.int("slash_defence_bonus") ?: 0,
            crushDefence = row.int("crush_defence_bonus") ?: 0,
            magicDefence = row.int("magic_defence_bonus") ?: 0,
            rangedDefence = row.int("range_defence_bonus") ?: 0,
            meleeStrength = row.int("strength_bonus") ?: 0,
            rangedStrength = row.int("ranged_strength_bonus") ?: 0,
            magicDamage = row.double("magic_damage_bonus") ?: 0.0,
            prayer = row.int("prayer_bonus") ?: 0,
        ),
        attackSpeed = row.int("weapon_attack_speed"),
        attackRange = row.int("weapon_attack_range")?.takeIf { it > 0 },
        combatStyle = row.str("combat_style"),
    )

    companion object {
        const val JOINED_ITEM_ID = "infobox_item.item_id"
        const val SECTION_NO_IDS = "Equipment pages with no item id in our cache"
        const val SECTION_DUPLICATES = "Item ids on more than one bonuses page"

        val FIELDS = listOf(
            "page_name", "page_name_sub", JOINED_ITEM_ID, "equipment_slot", "weapon_attack_speed", "weapon_attack_range",
            "combat_style", "stab_attack_bonus", "slash_attack_bonus", "crush_attack_bonus", "magic_attack_bonus",
            "range_attack_bonus", "stab_defence_bonus", "slash_defence_bonus", "crush_defence_bonus",
            "magic_defence_bonus", "range_defence_bonus", "strength_bonus", "ranged_strength_bonus",
            "magic_damage_bonus", "prayer_bonus",
        )
    }
}
