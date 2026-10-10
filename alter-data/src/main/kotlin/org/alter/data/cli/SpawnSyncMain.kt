package org.alter.data.cli

import dev.openrune.cache.CacheManager
import gg.rsmod.util.BuildInfo
import kotlinx.coroutines.runBlocking
import org.alter.data.cache.CacheManagerView
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.spawns.SpawnSync
import org.alter.data.spawns.WikiPageClient
import org.alter.data.wiki.RawCache
import org.alter.data.wiki.WikiBucketClient
import kotlin.system.exitProcess

/**
 * `./gradlew :alter-data:spawnSync [-PspawnArgs="--offline|--refresh"]`
 *
 * Regenerates the wiki entries in `data/cfg/spawns/npcs/` from the wiki's `{{Map}}` templates, keeping every
 * manual entry, and writes `data/reports/spawn-sync-<timestamp>.{json,md}`. Never touches `data/cfg/wiki`.
 * Exit codes: 0 written (or unchanged), 2 rejected (zero entries, or a fetch failed with no cached copy),
 * 1 error.
 */
fun main(args: Array<String>) {
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
