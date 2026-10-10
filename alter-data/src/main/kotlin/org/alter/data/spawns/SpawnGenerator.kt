package org.alter.data.spawns

import org.alter.data.cache.CacheView
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns wiki pages into NPC spawn entries and merges them with the existing region files.
 *
 * Per page: every Infobox NPC / Infobox Monster, every version's (map, id) pair ([InfoboxFields]), every
 * `{{Map}}` in the map value ([MapTemplateParser]). The id must be a single cache NPC with an RSCM name; the
 * entry gets the canonical (first) RSCM name for that id.
 *
 * Every generated entry gets its wiki id ([SpawnIds.wiki]). Merge rules:
 * - manual and edit entries are kept exactly as they are; every wiki entry is regenerated (stale ones disappear);
 * - wiki entries with the same (npc, x, z, height) collapse into the first in canonical order;
 * - a wiki entry whose id a manual or edit entry carries is dropped: that feature was edited in game and its
 *   wiki twin never comes back (`droppedClaimedById`);
 * - a wiki entry is dropped when a manual or edit entry for the same npc and height lies within
 *   max(walkRadius) of it (Chebyshev distance), so the hand-written spawn wins;
 * - the same npc in the same region but not overlapping is kept and reported as a possible duplicate;
 * - a manual or edit entry with a wiki id that is no longer generated is kept and listed as
 *   "Edit origins no longer generated" (`orphanedEdits`).
 *
 * Every skipped version or template is counted by reason ([SpawnSkip.key], plus `noInfobox`, `pageMissing`,
 * `noMapTemplate` and the id reasons) and listed by page in the report.
 */
class SpawnGenerator(private val cache: CacheView) {
    /** A fetched page; [wikitext] is null when the wiki reported it missing. */
    data class Page(val title: String, val wikitext: String?)

    data class Result(
        val entries: List<NpcSpawnEntry>,
        val manualKept: Int,
        val editsKept: Int,
        val wikiEntries: Int,
        val skips: Map<String, Int>,
        val droppedClaimedById: Int,
        val droppedOverlappedByManual: Int,
        val orphanedEdits: Int,
        val wikiDuplicatesCollapsed: Int,
        val possibleDuplicates: Int,
        val versionPairs: Int,
    )

    fun generate(pages: List<Page>, existing: List<NpcSpawnEntry>, report: ReportBuilder): Result {
        val skips = sortedMapOf<String, Int>()
        BASE_REASONS.forEach { skips[it] = 0 }
        fun skip(key: String, where: String, detail: String) {
            skips[key] = (skips[key] ?: 0) + 1
            report.add("Skipped: $key", "$where: $detail", Report.Severity.INFO)
        }

        var pairs = 0
        val generated = mutableListOf<NpcSpawnEntry>()
        for (page in pages.sortedBy { it.title }) {
            val text = page.wikitext
            if (text == null) {
                skip("pageMissing", page.title, "the wiki returned no content")
                continue
            }
            val boxes = InfoboxFields.infoboxes(text)
            if (boxes.isEmpty()) {
                skip("noInfobox", page.title, "no ${InfoboxFields.INFOBOX_NAMES.joinToString(" or ")}")
                continue
            }
            for (box in boxes) {
                for (pair in InfoboxFields.pairs(box)) {
                    pairs++
                    val where = page.title + (pair.version?.let { " (version $it${pair.label?.let { l -> ": $l" } ?: ""})" } ?: "")
                    val name = resolveName(pair.id, where, ::skip)
                    val templates = MapTemplateParser.templates(pair.map.orEmpty())
                    if (templates.isEmpty()) {
                        skip("noMapTemplate", where, "map = ${pair.map ?: "(absent)"}")
                        continue
                    }
                    for (template in templates) {
                        when (val r = MapTemplateParser.features(template)) {
                            is MapTemplateParser.Result.Skipped -> skip(r.skip.key, where, "${r.skip.detail}: ${template.raw}")
                            is MapTemplateParser.Result.Features -> if (name != null) {
                                r.features.forEach { f ->
                                    val url = pageUrl(page.title)
                                    generated += NpcSpawnEntry(
                                        id = SpawnIds.wiki(url, name, f.point.x, f.point.z, f.height),
                                        npc = name,
                                        x = f.point.x,
                                        z = f.point.z,
                                        height = f.height,
                                        walkRadius = SpawnRules.walkRadius(f.shape),
                                        source = NpcSpawnSource.Wiki(url, canonicalMap(template.raw)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Manual and edit entries: kept as they are, and they win over the wiki.
        val kept = existing.filter { it.source !is NpcSpawnSource.Wiki }
        val generatedIds = generated.mapTo(HashSet()) { it.id }

        // Exact wiki duplicates: keep the first in canonical order (page, then map).
        val byKey = generated.sortedWith(NpcSpawnFiles.canonicalOrder).groupBy { it.key }
        var collapsed = 0
        val unique = byKey.values.map { group ->
            if (group.size > 1) {
                collapsed += group.size - 1
                report.add(
                    "Wiki duplicates collapsed",
                    "${group[0].npc} at (${group[0].x}, ${group[0].z}, ${group[0].height}): kept ${page(group[0])}, dropped ${group.drop(1).joinToString { page(it) }}",
                    Report.Severity.INFO,
                )
            }
            group[0]
        }

        val claimed = kept.filter { it.id.startsWith(SpawnIds.WIKI_PREFIX) }.associateBy { it.id }
        var claimedDrops = 0
        val unclaimed = unique.filter { wiki ->
            val owner = claimed[wiki.id] ?: return@filter true
            claimedDrops++
            report.add(
                "Wiki entries claimed by id",
                "${wiki.id} ${wiki.npc} at (${wiki.x}, ${wiki.z}, ${wiki.height}) from ${page(wiki)}: ${owner.source.kind} entry at (${owner.x}, ${owner.z}, ${owner.height})",
                Report.Severity.INFO,
            )
            false
        }

        val keptByNpc = kept.groupBy { it.npc to it.height }
        var overlapped = 0
        val wiki = unclaimed.filter { w ->
            val hit = keptByNpc[w.npc to w.height]?.firstOrNull { overlaps(it, w) }
            if (hit != null) {
                overlapped++
                report.add(
                    "Wiki entries dropped for a manual entry",
                    "${w.npc} at (${w.x}, ${w.z}, ${w.height}) r${w.walkRadius} from ${page(w)}: ${hit.source.kind} at (${hit.x}, ${hit.z}) r${hit.walkRadius}",
                    Report.Severity.INFO,
                )
            }
            hit == null
        }

        val keptByRegion = kept.groupBy { it.npc to it.regionId }
        var possible = 0
        wiki.forEach { w ->
            keptByRegion[w.npc to w.regionId]?.forEach { m ->
                possible++
                report.add(
                    "Possible duplicates",
                    "${w.npc} in region ${w.regionId}: wiki (${w.x}, ${w.z}, ${w.height}) r${w.walkRadius} from ${page(w)}, ${m.source.kind} (${m.x}, ${m.z}, ${m.height}) r${m.walkRadius}",
                )
            }
        }

        val orphans = claimed.values.filter { it.id !in generatedIds }
        orphans.forEach {
            report.add(
                "Edit origins no longer generated",
                "${it.id} ${it.npc} at (${it.x}, ${it.z}, ${it.height}): its wiki feature${(it.source as? NpcSpawnSource.Edit)?.page?.let { p -> " on $p" } ?: ""} is gone; the entry is kept",
            )
        }

        return Result(
            entries = (kept + wiki).sortedWith(NpcSpawnFiles.canonicalOrder),
            manualKept = kept.count { it.source is NpcSpawnSource.Manual },
            editsKept = kept.count { it.source is NpcSpawnSource.Edit },
            wikiEntries = wiki.size,
            skips = skips,
            droppedClaimedById = claimedDrops,
            droppedOverlappedByManual = overlapped,
            orphanedEdits = orphans.size,
            wikiDuplicatesCollapsed = collapsed,
            possibleDuplicates = possible,
            versionPairs = pairs,
        )
    }

    private fun resolveName(raw: String?, where: String, skip: (String, String, String) -> Unit): String? =
        when (val id = InfoboxFields.id(raw)) {
            InfoboxFields.Id.Missing -> null.also { skip("noId", where, "no id") }
            is InfoboxFields.Id.Ambiguous -> null.also { skip("ambiguousIds", where, "id = ${id.ids.joinToString(",")}") }
            is InfoboxFields.Id.Invalid -> null.also { skip("badId", where, "id = ${id.raw}") }
            is InfoboxFields.Id.Single -> when {
                !cache.hasNpc(id.id) -> null.also { skip("idNotInCache", where, "npc ${id.id} is not in cache revision ${cache.revision}") }
                else -> cache.rscmName("npc", id.id) ?: null.also { skip("noRscmName", where, "npc ${id.id} has no RSCM name") }
            }
        }

    private fun page(e: NpcSpawnEntry) = (e.source as? NpcSpawnSource.Wiki)?.page?.removePrefix(NpcSpawnFiles.WIKI_PAGE_PREFIX) ?: "manual"

    companion object {
        /** Reasons always present in the report summary, zero or not; mtype-specific ones are added as they occur. */
        val BASE_REASONS = listOf(
            "pageMissing", "noInfobox", "noMapTemplate", "noId", "ambiguousIds", "badId", "idNotInCache", "noRscmName",
            "noMtype", "unsupportedMtype.polygon", "nonSurfaceMapId", "badPlane", "anonymousFeature", "badCoordinates",
            "noCoordinates", "fractionalR", "badShape",
        )

        /** Same npc and height assumed by the caller; within the larger walk radius of the two (Chebyshev). */
        fun overlaps(a: NpcSpawnEntry, b: NpcSpawnEntry): Boolean =
            max(abs(a.x - b.x), abs(a.z - b.z)) <= max(a.walkRadius, b.walkRadius)

        /**
         * The template as written, except that `{{map|` becomes `{{Map|`: MediaWiki reads the first letter of a
         * template name case-insensitively, and the region files require `source.map` to start with `{{Map`.
         */
        fun canonicalMap(raw: String): String = if (raw.startsWith("{{map")) "{{M" + raw.substring(3) else raw

        /** `https://oldschool.runescape.wiki/w/<Title>` with spaces as underscores and URL-unsafe characters escaped. */
        fun pageUrl(title: String): String = NpcSpawnFiles.WIKI_PAGE_PREFIX + title.trim().replace(' ', '_')
            .replace("%", "%25").replace("?", "%3F").replace("&", "%26").replace("\"", "%22").replace("#", "%23")
    }
}
