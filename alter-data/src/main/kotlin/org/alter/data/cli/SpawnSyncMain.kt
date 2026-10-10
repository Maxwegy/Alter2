package org.alter.data.cli

import dev.openrune.cache.CacheManager
import gg.rsmod.util.BuildInfo
import kotlinx.coroutines.runBlocking
import org.alter.data.cache.CacheManagerView
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.report.Report
import org.alter.data.spawns.NpcSpawnMigration
import org.alter.data.spawns.SpawnEditApplier
import org.alter.data.spawns.SpawnSync
import org.alter.data.spawns.WikiPageClient
import org.alter.data.wiki.RawCache
import org.alter.data.wiki.WikiBucketClient
import kotlin.system.exitProcess

/**
 * `./gradlew :alter-data:spawnSync [-PspawnArgs="--offline|--refresh|--apply-edits|--migrate"]`
 *
 * Regenerates the wiki entries in `data/cfg/spawns/npcs/` from the wiki's `{{Map}}` templates, keeping every
 * manual entry, and writes `data/reports/spawn-sync-<timestamp>.{json,md}`. Never touches `data/cfg/wiki`.
 * Exit codes: 0 written (or unchanged), 2 rejected (zero entries, or a fetch failed with no cached copy),
 * 1 error.
 *
 * `--apply-edits` instead applies the in-game spawn edits in `data/run/spawn-edits.jsonl` to the region files
 * ([SpawnEditApplier]); it is offline (no wiki, no cache) and writes `data/reports/spawn-apply-edits-<ts>.*`.
 * Exit codes: 0 applied (or nothing to apply; unmatched edits are reported), 2 rejected, 1 error.
 *
 * `--migrate` converts schema-1 region files to schema 2 ([NpcSpawnMigration]); offline, writes
 * `data/reports/spawn-migrate-<ts>.*`. Exit codes: 0 migrated (or already schema 2), 2 rejected, 1 error.
 */
fun main(args: Array<String>) {
    if ("--migrate" in args) exitProcess(migrate(DataPaths.default()))
    if ("--apply-edits" in args) exitProcess(applyEdits(DataPaths.default()))
    val options = SpawnSync.Options(offline = "--offline" in args, refresh = "--refresh" in args)
    val paths = DataPaths.default()
    val config = InfraConfig.load(paths.config)

    val exitCode = try {
        println("Loading cache revision ${BuildInfo.REVISION} from ${paths.cache.toAbsolutePath().normalize()}...")
        CacheManager.init(paths.cache, BuildInfo.REVISION)
        val cache = CacheManagerView(BuildInfo.REVISION, paths.dataDir.resolve("cfg/rscm"))

        val http = if (options.offline) null else WikiHttpClient(config.wiki)
        if (http != null) println("User-Agent: ${http.userAgent}")
        val result = try {
            runBlocking {
                SpawnSync(http?.let(::WikiPageClient), http?.let(::WikiBucketClient), RawCache(paths.wikiCache), cache, config.wiki, paths.npcSpawns)
                    .run(options)
            }
        } finally {
            http?.close()
        }
        println("Wiki requests sent: ${http?.requestCount ?: 0}")

        val reportPath = result.report.write(paths.reports)
        result.report.summary.forEach { (key, value) -> println("  $key: $value") }
        println("Report: ${reportPath.toAbsolutePath().normalize()}")
        when (result) {
            is SpawnSync.Result.Written -> {
                println(if (result.write.written.isEmpty() && result.write.removed.isEmpty()) "Spawn files unchanged." else "Spawn files updated.")
                0
            }
            is SpawnSync.Result.Rejected -> {
                System.err.println("Spawn sync rejected, region files left untouched: ${result.reason}")
                2
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun applyEdits(paths: DataPaths): Int = try {
    val result = SpawnEditApplier.run(paths.npcSpawns, paths.spawnEdits)
    if (result is SpawnEditApplier.Result.NothingToApply) {
        println("No spawn edits to apply in ${paths.spawnEdits.toAbsolutePath().normalize()}.")
        0
    } else {
        val reportPath = result.report.write(paths.reports)
        result.report.summary.forEach { (key, value) -> println("  $key: $value") }
        println("Report: ${reportPath.toAbsolutePath().normalize()}")
        when (result) {
            is SpawnEditApplier.Result.Written -> {
                result.applied.unmatched.forEach { System.err.println("Not applied: $it") }
                println("Applied ${result.applied.applied} spawn edit(s); outbox kept as ${result.archived.fileName}.")
                0
            }
            is SpawnEditApplier.Result.Rejected -> {
                System.err.println("Spawn edits rejected, region files left untouched: ${result.reason}")
                2
            }
            is SpawnEditApplier.Result.NothingToApply -> 0
        }
    }
} catch (e: Exception) {
    e.printStackTrace()
    1
}

private fun migrate(paths: DataPaths): Int = try {
    val result = NpcSpawnMigration.run(paths.npcSpawns)
    val reportPath = result.report.write(paths.reports)
    result.report.summary.forEach { (key, value) -> println("  $key: $value") }
    println("Report: ${reportPath.toAbsolutePath().normalize()}")
    when (result) {
        is NpcSpawnMigration.Result.Migrated -> {
            println("Migrated ${result.files} region files to schema 2 (${result.manual} manual, ${result.wiki} wiki).")
            0
        }
        is NpcSpawnMigration.Result.AlreadyMigrated -> {
            println("Spawn files are already schema 2; nothing written.")
            0
        }
        is NpcSpawnMigration.Result.Rejected -> {
            result.report.sections.filter { it.severity == Report.Severity.ERROR }
                .forEach { section -> section.items.forEach { System.err.println("${section.title}: $it") } }
            System.err.println("Spawn migration rejected, region files left untouched: ${result.reason}")
            2
        }
    }
} catch (e: Exception) {
    e.printStackTrace()
    1
}
