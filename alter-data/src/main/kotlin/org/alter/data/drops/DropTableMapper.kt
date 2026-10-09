package org.alter.data.drops

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.cache.CacheView
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import org.alter.data.snapshot.Drop
import org.alter.data.snapshot.DropPage
import org.alter.data.snapshot.DropTable
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.ids
import org.alter.data.wiki.str

/**
 * Turns the wiki's flat `dropsline` rows into per-source drop tables with exact chances.
 *
 * Classification, per line:
 * - `Always` (or a chance of 1) → always
 * - item name matches a tertiary pattern or is a pet → tertiary (independent roll)
 * - everything else → main. The wiki already flattens sub-tables (rare drop table, gems, herbs) into
 *   per-item chances that are exclusive slots of the main roll, so they stay in main.
 *
 * If a source's main chances add up to more than 1 (per roll count), some lines must really be tertiary:
 * the table is marked `needsReview` and all its non-always lines become independent rolls, so each item
 * keeps its wiki per-kill rate. The report lists these for a human (or a drop override) to fix.
 */
class DropTableMapper(
    private val cache: CacheView,
    private val names: ItemNameResolver,
    tertiaryPatterns: List<String>,
    pets: Collection<String>,
) {
    private val tertiary = tertiaryPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
    private val petNames = pets.map { it.lowercase() }.toSet()

    fun map(dropRows: List<BucketRow>, monsterRows: List<BucketRow>, report: ReportBuilder): List<DropPage> {
        val idsBySub = HashMap<String, MutableSet<Int>>()
        val idsByPage = HashMap<String, MutableSet<Int>>()
        monsterRows.forEach { row ->
            val ids = row.ids("id").filter(cache::hasNpc)
            row.str("page_name_sub")?.let { idsBySub.getOrPut(it, ::mutableSetOf) += ids }
            row.str("page_name")?.let { idsByPage.getOrPut(it, ::mutableSetOf) += ids }
        }

        val linesBySource = dropRows.mapNotNull { row -> parse(row) }
            .filter { it.json["Drop type"] == "combat" }
            .groupBy { it.source }

        val tables = linesBySource.toSortedMap().mapNotNull { (source, lines) ->
            // A version-specific source only ever maps to that version; fall back to the whole page only
            // when the wiki has no monster row for the source (single-table pages).
            val npcIds = (idsBySub[source] ?: idsByPage[lines.first().page])?.sorted().orEmpty()
            if (npcIds.isEmpty()) {
                report.add(SECTION_UNMATCHED_SOURCE, "$source (${lines.size} lines)", Report.Severity.INFO)
                return@mapNotNull null
            }
            buildTable(source, npcIds, lines, report)
        }
        return tables.groupBy { it.source.substringBefore('#') }
            .map { (page, pageTables) -> DropPage(page, pageTables) }
            .sortedBy { it.page }
    }

    private fun buildTable(source: String, npcIds: List<Int>, lines: List<Line>, report: ReportBuilder): DropTable {
        val always = mutableListOf<Drop>()
        val main = mutableListOf<Drop>()
        val independent = mutableListOf<Drop>()

        lines.forEach { line ->
            val itemName = line.json["Dropped item"]?.toString() ?: line.itemName ?: return@forEach
            if (itemName.equals("Nothing", ignoreCase = true)) return@forEach
            val rarityText = line.json["Rarity"]?.toString()
            val rarity = RarityParser.parse(rarityText)
            if (rarity == null) {
                report.add(SECTION_UNPARSEABLE_RARITY, "$source: $itemName '${rarityText ?: ""}'")
                return@forEach
            }
            val drop = toDrop(source, itemName, line, rarity, report) ?: return@forEach
            when {
                rarity is Rarity.Always -> always += drop
                isTertiary(itemName) -> independent += drop
                else -> main += drop
            }
        }

        // Exact sums overflow (flattened sub-table chances have large denominators), and the wiki rounds those
        // decimals anyway, so compare in doubles with a small tolerance.
        val overfull = main.groupBy { it.rolls }.any { (_, drops) -> drops.sumOf { Fraction.fromPair(it.chance!!).value } > 1.0 + OVERFULL_TOLERANCE }
        if (overfull) {
            report.add(SECTION_NEEDS_REVIEW, "$source: main-table chances exceed 1; rolled independently")
        }
        return DropTable(
            source = source,
            npcIds = npcIds,
            needsReview = overfull,
            always = always.sortedWith(DROP_ORDER),
            main = if (overfull) emptyList() else main.sortedWith(DROP_ORDER),
            tertiary = (if (overfull) independent + main else independent).sortedWith(DROP_ORDER),
        )
    }

    private fun toDrop(source: String, itemName: String, line: Line, rarity: Rarity, report: ReportBuilder): Drop? {
        val baseId = names.resolve(itemName)
        if (baseId == null) {
            report.add(SECTION_UNRESOLVED_ITEM, "$source: $itemName")
            return null
        }
        val quantityText = line.json["Drop Quantity"]?.toString() ?: ""
        val wantsNoted = quantityText.contains("noted", ignoreCase = true)
        val id = if (wantsNoted) {
            cache.notedId(baseId) ?: baseId.also { report.add(SECTION_NO_NOTED_VARIANT, "$source: $itemName ($baseId)", Report.Severity.INFO) }
        } else {
            baseId
        }
        val (min, max) = quantity(line.json, quantityText) ?: run {
            report.add(SECTION_UNPARSEABLE_QUANTITY, "$source: $itemName '$quantityText'")
            return null
        }
        val chance = (rarity as? Rarity.Chance)?.fraction
        return Drop(
            item = id,
            name = cache.rscmName("item", id),
            min = min,
            max = max,
            noted = wantsNoted && id != baseId,
            chance = chance?.toPair(),
            rolls = (line.json["Rolls"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
            wiki = line.json["Rarity"]?.toString()?.takeIf { rarity is Rarity.Chance },
            approx = (rarity as? Rarity.Chance)?.approximate == true || line.json["Approx"] == true,
        )
    }

    /** Quantity Low/High when present; otherwise parse "5", "5-10", "5–10" or "60 (noted)". */
    private fun quantity(json: Map<String, Any?>, text: String): Pair<Int, Int>? {
        val low = (json["Quantity Low"] as? Number)?.toInt()
        val high = (json["Quantity High"] as? Number)?.toInt()
        if (low != null && high != null && low > 0 && high >= low) return low to high
        val match = QUANTITY.find(text.replace(",", "")) ?: return null
        val a = match.groupValues[1].toInt()
        val b = match.groupValues[2].toIntOrNull() ?: a
        return if (a in 1..b) a to b else null
    }

    private fun isTertiary(itemName: String): Boolean =
        itemName.lowercase() in petNames || tertiary.any { it.containsMatchIn(itemName) }

    private fun parse(row: BucketRow): Line? {
        val source = row.str("page_name_sub") ?: return null
        val json = row.str("drop_json")?.let { runCatching { mapper.readValue<Map<String, Any?>>(it) }.getOrNull() } ?: return null
        return Line(source, row.str("page_name") ?: source.substringBefore('#'), row.str("item_name"), json)
    }

    private data class Line(val source: String, val page: String, val itemName: String?, val json: Map<String, Any?>)

    companion object {
        const val SECTION_UNMATCHED_SOURCE = "Drop sources with no NPC in our cache"
        const val SECTION_UNPARSEABLE_RARITY = "Drop lines with a non-numeric rarity (skipped)"
        const val SECTION_UNRESOLVED_ITEM = "Drop item names not found in our cache (skipped)"
        const val SECTION_UNPARSEABLE_QUANTITY = "Drop lines with an unknown quantity (skipped)"
        const val SECTION_NO_NOTED_VARIANT = "Noted drops whose item has no noted variant (dropped unnoted)"
        const val SECTION_NEEDS_REVIEW = "Drop tables that need review"

        val FIELDS = listOf("page_name", "page_name_sub", "item_name", "drop_json", "rare_drop_table")

        private const val OVERFULL_TOLERANCE = 1e-6
        private val QUANTITY = Regex("""(\d+)\s*(?:[-–]\s*(\d+))?""")
        private val DROP_ORDER = compareBy<Drop>({ it.item }, { it.min }, { it.max }, { it.chance?.get(1) ?: 0 }, { it.chance?.get(0) ?: 0 }, { it.rolls })
        private val mapper = ObjectMapper().registerKotlinModule()
    }
}
