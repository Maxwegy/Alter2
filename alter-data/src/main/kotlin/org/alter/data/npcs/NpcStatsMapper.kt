package org.alter.data.npcs

import org.alter.data.cache.CacheView
import org.alter.data.report.ReportBuilder
import org.alter.data.snapshot.ElementalWeakness
import org.alter.data.snapshot.NpcBonuses
import org.alter.data.snapshot.NpcEntry
import org.alter.data.snapshot.NpcImmunities
import org.alter.data.snapshot.NpcLevels
import org.alter.data.snapshot.NpcPage
import org.alter.data.snapshot.NpcSlayer
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.bool
import org.alter.data.wiki.double
import org.alter.data.wiki.ids
import org.alter.data.wiki.int
import org.alter.data.wiki.str
import org.alter.data.wiki.strings

/** Maps `infobox_monster` rows to normalized [NpcPage]s, keeping only NPC ids that exist in our cache. */
class NpcStatsMapper(private val cache: CacheView) {
    fun map(rows: List<BucketRow>, report: ReportBuilder): List<NpcPage> {
        val entries = rows.mapNotNull { row -> toEntry(row, report) }
        return entries.groupBy { it.source.substringBefore('#') }
            .map { (page, versions) -> NpcPage(page, versions.sortedBy { it.source }) }
            .sortedBy { it.page }
    }

    private fun toEntry(row: BucketRow, report: ReportBuilder): NpcEntry? {
        val source = row.str("page_name_sub") ?: row.str("page_name") ?: return null
        val wikiIds = row.ids("id")
        val ids = wikiIds.filter(cache::hasNpc).distinct().sorted()
        if (ids.isEmpty()) {
            report.add(SECTION_NO_IDS, "$source (wiki ids ${wikiIds.ifEmpty { listOf("none") }})", org.alter.data.report.Report.Severity.INFO)
            return null
        }
        (wikiIds - ids.toSet()).forEach { report.add(SECTION_IDS_DROPPED, "$source: $it", org.alter.data.report.Report.Severity.INFO) }

        return NpcEntry(
            source = source,
            version = row.str("version_anchor"),
            ids = ids,
            names = ids.mapNotNull { cache.rscmName("npc", it) },
            combatLevel = row.int("combat_level"),
            hitpoints = row.int("hitpoints"),
            attackSpeed = row.int("attack_speed"),
            respawnTicks = row.int("respawn_time"),
            size = row.int("size"),
            levels = levels(row),
            bonuses = bonuses(row),
            maxHits = row.strings("max_hit"),
            attackStyles = row.strings("attack_style"),
            aggressive = row.str("is_aggressive")?.let(::yes),
            poisonous = row.str("poisonous")?.let(::yes),
            immunities = NpcImmunities(
                poison = immune(row.str("poison_resistance")),
                venom = immune(row.str("venom_resistance")),
                cannon = immune(row.str("cannon_immune")),
                thrall = immune(row.str("thrall_immune")),
                burn = row.str("burn_immune"),
                freeze = row.str("freeze_resistance"),
            ),
            attributes = row.strings("attribute").map { it.lowercase() }.sorted(),
            slayer = slayer(row),
            elementalWeakness = row.str("elemental_weakness")
                ?.takeUnless { it.equals("none", ignoreCase = true) }
                ?.let { ElementalWeakness(it, row.int("elemental_weakness_percent")) },
        )
    }

    private fun levels(row: BucketRow): NpcLevels? {
        val attack = row.int("attack_level")
        val strength = row.int("strength_level")
        val defence = row.int("defence_level")
        val ranged = row.int("ranged_level")
        val magic = row.int("magic_level")
        if (listOf(attack, strength, defence, ranged, magic).all { it == null }) return null
        return NpcLevels(attack ?: 1, strength ?: 1, defence ?: 1, ranged ?: 1, magic ?: 1)
    }

    private fun bonuses(row: BucketRow): NpcBonuses? {
        val fields = listOf(
            "stab_attack_bonus", "slash_attack_bonus", "crush_attack_bonus", "magic_attack_bonus", "range_attack_bonus",
            "stab_defence_bonus", "slash_defence_bonus", "crush_defence_bonus", "magic_defence_bonus", "range_defence_bonus",
            "standard_range_defence_bonus", "attack_bonus", "strength_bonus", "range_strength_bonus", "magic_damage_bonus",
        )
        if (fields.all { row[it] == null }) return null
        return NpcBonuses(
            stabAttack = row.int("stab_attack_bonus") ?: 0,
            slashAttack = row.int("slash_attack_bonus") ?: 0,
            crushAttack = row.int("crush_attack_bonus") ?: 0,
            magicAttack = row.int("magic_attack_bonus") ?: 0,
            rangedAttack = row.int("range_attack_bonus") ?: 0,
            stabDefence = row.int("stab_defence_bonus") ?: 0,
            slashDefence = row.int("slash_defence_bonus") ?: 0,
            crushDefence = row.int("crush_defence_bonus") ?: 0,
            magicDefence = row.int("magic_defence_bonus") ?: 0,
            rangedDefence = row.int("standard_range_defence_bonus") ?: row.int("range_defence_bonus") ?: 0,
            lightRangedDefence = row.int("light_range_defence_bonus"),
            heavyRangedDefence = row.int("heavy_range_defence_bonus"),
            attackBonus = row.int("attack_bonus") ?: 0,
            strengthBonus = row.int("strength_bonus") ?: 0,
            rangedStrength = row.int("range_strength_bonus") ?: 0,
            magicDamage = row.int("magic_damage_bonus") ?: 0,
        )
    }

    private fun slayer(row: BucketRow): NpcSlayer? {
        val level = row.int("slayer_level")
        val xp = row.double("slayer_experience")
        val categories = row.strings("slayer_category").sorted()
        val assignedBy = row.strings("assigned_by").map { it.lowercase() }.sorted()
        if (level == null && xp == null && categories.isEmpty() && assignedBy.isEmpty()) return null
        return NpcSlayer(level, xp, categories, assignedBy)
    }

    private fun yes(text: String) = text.lowercase().startsWith("yes")

    /** "Immune", "Immune (weak)" and "100" count as immune; "Not immune" and "0" do not. */
    private fun immune(text: String?): Boolean {
        val value = text?.lowercase() ?: return false
        return (value.startsWith("immune") || value == "100" || value == "yes")
    }

    companion object {
        const val SECTION_NO_IDS = "Monster rows with no NPC id in our cache"
        const val SECTION_IDS_DROPPED = "Wiki NPC ids not in our cache"

        /** Every field the mapper reads; the sync selects exactly these. */
        val FIELDS = listOf(
            "page_name", "page_name_sub", "id", "version_anchor", "default_version", "combat_level", "hitpoints",
            "attack_speed", "respawn_time", "size", "attack_level", "strength_level", "defence_level", "ranged_level",
            "magic_level", "stab_attack_bonus", "slash_attack_bonus", "crush_attack_bonus", "magic_attack_bonus",
            "range_attack_bonus", "stab_defence_bonus", "slash_defence_bonus", "crush_defence_bonus",
            "magic_defence_bonus", "range_defence_bonus", "light_range_defence_bonus", "standard_range_defence_bonus",
            "heavy_range_defence_bonus", "attack_bonus", "strength_bonus", "range_strength_bonus", "magic_damage_bonus",
            "max_hit", "attack_style", "is_aggressive", "poisonous", "poison_resistance", "venom_resistance",
            "cannon_immune", "thrall_immune", "burn_immune", "freeze_resistance", "attribute", "slayer_level",
            "slayer_experience", "slayer_category", "assigned_by", "elemental_weakness", "elemental_weakness_percent",
        )
    }
}
