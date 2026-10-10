package org.alter.data.spawns

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.cache.CacheView
import org.alter.data.config.InfraConfig
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import org.alter.data.wiki.BucketQuery
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.RawCache
import org.alter.data.wiki.WikiBucketClient
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * fetch → generate → merge → write → report, for `data/cfg/spawns/npcs` only (it never touches the wiki
 * snapshot in `data/cfg/wiki`, and the server's `::wikisync` does not run it).
 *
 * Inputs: the main-namespace pages that embed Template:Map, intersected with the pages that have an
 * `infobox_npc` or `infobox_monster` Bucket row (the Bucket has no coordinates, so wikitext is the only
 * `{{Map}}` source), and those pages' wikitext. Raw responses live in the gitignored wiki cache
 * ([ENTRIES]); [Options.offline] reads only that cache, otherwise entries younger than the TTL are reused
 * unless [Options.refresh] is set. A failed fetch falls back to an older cached copy when there is one.
 */
class SpawnSync(
    private val pages: WikiPageClient?,
    private val buckets: WikiBucketClient?,
    private val rawCache: RawCache,
    private val cache: CacheView,
    private val config: InfraConfig.Wiki,
    private val spawnDir: Path,
) {
    private val logger = KotlinLogging.logger {}

    data class Options(val offline: Boolean = false, val refresh: Boolean = false)

    sealed interface Result {
        val report: Report

        data class Written(val write: NpcSpawnFiles.WriteResult, val generated: SpawnGenerator.Result, override val report: Report) : Result

        /** Nothing was written: [reason] says why (no input, zero entries, unreadable region files). */
        data class Rejected(val reason: String, override val report: Report) : Result
    }

    private class Unavailable(message: String) : Exception(message)

    suspend fun run(options: Options = Options()): Result {
        val report = ReportBuilder(TOOL)
        report.summary("offline", options.offline)
        report.summary("cacheRevision", cache.revision)
        fun reject(reason: String): Result {
            report.add("Sync rejected", reason, Report.Severity.ERROR)
            return Result.Rejected(reason, report.build())
        }

        val existing = when (val read = NpcSpawnFiles.readAll(spawnDir)) {
            NpcSpawnFiles.ReadResult.Missing -> emptyList()
            is NpcSpawnFiles.ReadResult.Read -> {
                if (read.errors.isNotEmpty()) {
                    read.errors.forEach { report.add("Region file errors", it, Report.Severity.ERROR) }
                    return reject("${read.errors.size} problem(s) in the existing region files; fix them first.")
                }
                read.entries
            }
        }

        val input = try {
            fetchInput(options, report)
        } catch (e: Unavailable) {
            return reject(e.message ?: "wiki data unavailable")
        }

        val generated = SpawnGenerator(cache).generate(input, existing, report)
        report.summary("versionPairs", generated.versionPairs)
        generated.skips.forEach { (reason, count) -> report.summary("skip.$reason", count) }
        report.summary("wikiDuplicatesCollapsed", generated.wikiDuplicatesCollapsed)
        report.summary("droppedOverlappedByManual", generated.droppedOverlappedByManual)
        report.summary("possibleDuplicates", generated.possibleDuplicates)
        report.summary("manualKept", generated.manualKept)
        report.summary("wikiEntries", generated.wikiEntries)
        report.summary("entries", generated.entries.size)
        if (generated.wikiEntries == 0) return reject("The wiki produced zero spawn entries; region files left untouched.")

        val files = NpcSpawnFiles.group(generated.entries)
        report.summary("regionFiles", files.size)
        // Never write a file the boot loader (or the next sync) would reject.
        val invalid = mutableListOf<String>()
        files.forEach { NpcSpawnFiles.parse("${it.regionId}.json", NpcSpawnFiles.render(it), invalid) }
        if (invalid.isNotEmpty()) {
            invalid.forEach { report.add("Generated entries the reader rejects", it, Report.Severity.ERROR) }
            return reject("${invalid.size} generated problem(s); region files left untouched.")
        }
        val written = NpcSpawnFiles.write(spawnDir, files)
        report.summary("filesWritten", written.written.size)
        report.summary("filesRemoved", written.removed.size)
        written.written.forEach { report.add("Changed files", it, Report.Severity.INFO) }
        written.removed.forEach { report.add("Removed files", it, Report.Severity.INFO) }
        return Result.Written(written, generated, report.build())
    }

    private suspend fun fetchInput(options: Options, report: ReportBuilder): List<SpawnGenerator.Page> {
        val embedded = cached(EMBEDDED_IN, "embeddedin $TEMPLATE ns0", options) {
            pages!!.embeddedIn(TEMPLATE, 0).map { mapOf("title" to it) }
        }.mapNotNull { it["title"] as? String }.toSortedSet()
        val infoboxPages = sortedSetOf<String>()
        BUCKETS.forEach { (name, query) ->
            val rows = cached(name, query.build(), options) { buckets!!.fetchAll(query) }
            report.summary("rows.$name", rows.size)
            rows.mapNotNullTo(infoboxPages) { it["page_name"] as? String }
        }
        val candidates = embedded.filter { it in infoboxPages }
        report.summary("pages.embeddingMap", embedded.size)
        report.summary("pages.withNpcInfobox", infoboxPages.size)
        report.summary("pages.candidates", candidates.size)
        if (candidates.isEmpty()) throw Unavailable("No page both embeds $TEMPLATE and has an NPC or monster infobox.")

        val texts = cached(PAGES, "wikitext of ${candidates.size} pages", options, accept = { rows ->
            // A cached page list is only reused when it covers exactly these candidates.
            rows.mapNotNull { it["title"] as? String }.toSortedSet() == candidates.toSortedSet()
        }) {
            pages!!.wikitext(candidates) { done, total -> if (done % 500 == 0 || done == total) logger.info { "Fetched wikitext of $done/$total pages." } }
                .map { (title, text) -> mapOf("title" to title, "wikitext" to text) }
        }
        val result = texts.map { SpawnGenerator.Page(it["title"] as String, it["wikitext"] as? String) }
        report.summary("pages.fetched", result.count { it.wikitext != null })
        return result
    }

    /** Raw rows for [name]: from the cache when allowed and fresh, else fetched (falling back to a stale copy on failure). */
    private suspend fun cached(
        name: String,
        description: String,
        options: Options,
        accept: (List<BucketRow>) -> Boolean = { true },
        fetch: suspend () -> List<BucketRow>,
    ): List<BucketRow> {
        if (options.offline) {
            val entry = rawCache.read(name) ?: throw Unavailable("--offline but no raw cache for '$name'; run spawnSync online first.")
            if (!accept(entry.rows)) throw Unavailable("--offline but the cached '$name' does not match the current page list; run spawnSync online.")
            return entry.rows
        }
        if (!options.refresh) {
            rawCache.read(name, Duration.ofHours(config.rawCacheTtlHours))?.takeIf { accept(it.rows) }?.let {
                logger.info { "Using cached '$name' (${it.rows.size} rows, fetched ${it.fetchedAt})." }
                return it.rows
            }
        }
        if (pages == null || buckets == null) throw Unavailable("No wiki client configured.")
        logger.info { "Fetching '$name' from the wiki..." }
        val rows = try {
            fetch()
        } catch (e: IOException) {
            val stale = rawCache.read(name)?.takeIf { accept(it.rows) }
                ?: throw Unavailable("Fetching '$name' failed (${e.message}) and there is no cached copy.")
            logger.warn { "Fetching '$name' failed (${e.message}); using the cached copy from ${stale.fetchedAt}." }
            return stale.rows
        }
        rawCache.write(RawCache.Entry(name, description, Instant.now().toString(), rows))
        logger.info { "Fetched ${rows.size} '$name' rows." }
        return rows
    }

    companion object {
        const val TOOL = "spawn-sync"
        const val TEMPLATE = "Template:Map"
        const val EMBEDDED_IN = "map_embeddedin"
        const val PAGES = "map_pages"

        /** Pages with an NPC or monster infobox; only `page_name` is needed (one row per version). */
        val BUCKETS: Map<String, BucketQuery> = linkedMapOf(
            "map_infobox_npc" to BucketQuery("infobox_npc").select("page_name", "page_name_sub").orderBy("page_name_sub"),
            "map_infobox_monster" to BucketQuery("infobox_monster").select("page_name", "page_name_sub").orderBy("page_name_sub"),
        )

        /** Every raw cache entry this tool reads or writes. */
        val ENTRIES = listOf(EMBEDDED_IN, *BUCKETS.keys.toTypedArray(), PAGES)
    }
}
