package org.alter.data.wiki

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.cache.CacheView
import org.alter.data.config.InfraConfig
import org.alter.data.drops.DropTableMapper
import org.alter.data.drops.ItemNameResolver
import org.alter.data.http.WikiHttpClient
import org.alter.data.items.ItemStatsMapper
import org.alter.data.npcs.NpcStatsMapper
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import org.alter.data.snapshot.Manifest
import org.alter.data.snapshot.Snapshot
import org.alter.data.snapshot.SnapshotWriter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * fetch → normalize → validate → write → report.
 *
 * The only code that talks to the wiki. With [Options.offline] it never touches the network and only
 * re-normalizes the raw cache; otherwise raw responses younger than the TTL are reused unless
 * [Options.refresh] is set.
 */
class WikiSync(
    private val client: WikiBucketClient?,
    private val rawCache: RawCache,
    private val cache: CacheView,
    private val config: InfraConfig.Wiki,
    private val snapshotDir: Path,
) {
    private val logger = KotlinLogging.logger {}

    data class Options(val offline: Boolean = false, val refresh: Boolean = false)

    sealed interface Result {
        val report: Report

        data class Written(val result: SnapshotWriter.Result, override val report: Report) : Result

        data class Rejected(val reason: String, override val report: Report) : Result
    }

    suspend fun run(options: Options = Options()): Result {
        val report = ReportBuilder("wiki-sync")
        val datasets = QUERIES.mapValues { (name, query) -> fetch(name, query, options) }
        val rowCounts = datasets.mapValues { it.value.size }
        report.summary("offline", options.offline)
        report.summary("cacheRevision", cache.revision)
        rowCounts.forEach { (name, count) -> report.summary("rows.$name", count) }

        rejectReason(rowCounts)?.let { reason ->
            report.add("Sync rejected", reason, Report.Severity.ERROR)
            return Result.Rejected(reason, report.build())
        }

        val names = ItemNameResolver(datasets.getValue(ITEMS), cache)
        val pets = datasets.getValue(PETS).mapNotNull { it.str("item_name") }
        val snapshot = Snapshot(
            items = ItemStatsMapper(cache).map(datasets.getValue(BONUSES), report),
            npcs = NpcStatsMapper(cache).map(datasets.getValue(MONSTERS), report),
            drops = DropTableMapper(cache, names, config.tertiaryPatterns, pets).map(datasets.getValue(DROPS), datasets.getValue(MONSTERS), report),
        )
        report.summary("items", snapshot.items.size)
        report.summary("npcPages", snapshot.npcs.size)
        report.summary("npcIds", snapshot.npcs.sumOf { page -> page.versions.sumOf { it.ids.size } })
        report.summary("dropTables", snapshot.drops.sumOf { it.tables.size })
        report.summary("dropTablesNeedingReview", report.count(DropTableMapper.SECTION_NEEDS_REVIEW))

        val written = SnapshotWriter(snapshotDir).write(
            snapshot,
            generator = "Alter2-DataSync/${WikiHttpClient.VERSION}",
            cacheRevision = cache.revision,
            sources = listOf(WikiHttpClient.DEFAULT_API + " (Bucket: " + QUERIES.values.map { it.bucket }.distinct().joinToString() + ")"),
            rowCounts = rowCounts,
        )
        report.summary("filesWritten", written.written.size)
        report.summary("filesRemoved", written.removed.size)
        written.written.forEach { report.add("Changed files", it, Report.Severity.INFO) }
        written.removed.forEach { report.add("Removed files", it, Report.Severity.INFO) }
        return Result.Written(written, report.build())
    }

    private suspend fun fetch(name: String, query: BucketQuery, options: Options): List<BucketRow> {
        val maxAge = Duration.ofHours(config.rawCacheTtlHours)
        if (options.offline) {
            return rawCache.read(name)?.rows ?: error("--offline but no raw cache for '$name' in the wiki cache; run a normal sync first.")
        }
        if (!options.refresh) {
            rawCache.read(name, maxAge)?.let {
                logger.info { "Using cached '$name' (${it.rows.size} rows, fetched ${it.fetchedAt})." }
                return it.rows
            }
        }
        val client = client ?: error("No wiki client configured.")
        logger.info { "Fetching '$name' from the wiki..." }
        val rows = client.fetchAll(query)
        rawCache.write(RawCache.Entry(name, query.build(), Instant.now().toString(), rows))
        logger.info { "Fetched ${rows.size} '$name' rows." }
        return rows
    }

    /** Refuses to replace the committed snapshot when a dataset shrinks suspiciously (API breakage, outage). */
    private fun rejectReason(rowCounts: Map<String, Int>): String? {
        val manifestFile = snapshotDir.resolve(SnapshotWriter.MANIFEST)
        rowCounts.filter { it.value == 0 }.keys.firstOrNull()?.let { return "Dataset '$it' returned no rows." }
        if (!Files.exists(manifestFile)) return null
        val previous = runCatching { manifestMapper.readValue<Manifest>(manifestFile.toFile()).rowCounts }.getOrNull() ?: return null
        rowCounts.forEach { (name, count) ->
            val before = previous[name] ?: return@forEach
            if (before > 0 && count < before * (100 - config.maxRowDropPercent) / 100) {
                return "Dataset '$name' shrank from $before to $count rows (more than ${config.maxRowDropPercent}%)."
            }
        }
        return null
    }

    companion object {
        const val MONSTERS = "infobox_monster"
        const val BONUSES = "infobox_bonuses"
        const val ITEMS = "infobox_item"
        const val DROPS = "dropsline"
        const val PETS = "pets"

        val QUERIES: Map<String, BucketQuery> = linkedMapOf(
            MONSTERS to BucketQuery(MONSTERS).select(*NpcStatsMapper.FIELDS.toTypedArray()).orderBy("page_name_sub"),
            BONUSES to BucketQuery(BONUSES)
                .join(ITEMS, "$ITEMS.page_name_sub", "$BONUSES.page_name_sub")
                .select(*ItemStatsMapper.FIELDS.toTypedArray())
                .orderBy("page_name_sub"),
            ITEMS to BucketQuery(ITEMS).select(*ItemNameResolver.FIELDS.toTypedArray()).orderBy("page_name_sub"),
            DROPS to BucketQuery(DROPS).select(*DropTableMapper.FIELDS.toTypedArray()).orderBy("page_name_sub"),
            PETS to BucketQuery(ITEMS).select("page_name", "item_name").whereCategory("Pets"),
        )

        private val manifestMapper = ObjectMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
