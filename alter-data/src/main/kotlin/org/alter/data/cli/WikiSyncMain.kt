package org.alter.data.cli

import dev.openrune.cache.CacheManager
import gg.rsmod.util.BuildInfo
import kotlinx.coroutines.runBlocking
import org.alter.data.cache.CacheManagerView
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.wiki.RawCache
import org.alter.data.wiki.WikiBucketClient
import org.alter.data.wiki.WikiSync
import kotlin.system.exitProcess

/**
 * `./gradlew :alter-data:wikiSync [-PwikiArgs="--offline|--refresh"]`
 *
 * Regenerates the committed snapshot in `data/cfg/wiki/` without booting the server.
 * Exit codes: 0 written (or unchanged), 2 rejected by validation, 1 error.
 */
fun main(args: Array<String>) {
    val options = WikiSync.Options(offline = "--offline" in args, refresh = "--refresh" in args)
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
                WikiSync(http?.let(::WikiBucketClient), RawCache(paths.wikiCache), cache, config.wiki, paths.wikiSnapshot).run(options)
            }
        } finally {
            http?.close()
        }
        println("Wiki requests sent: ${http?.requestCount ?: 0}")

        val reportPath = result.report.write(paths.reports)
        result.report.summary.forEach { (key, value) -> println("  $key: $value") }
        println("Report: ${reportPath.toAbsolutePath().normalize()}")
        when (result) {
            is WikiSync.Result.Written -> {
                println(if (result.result.changed) "Snapshot updated." else "Snapshot unchanged.")
                0
            }
            is WikiSync.Result.Rejected -> {
                System.err.println("Sync rejected, snapshot left untouched: ${result.reason}")
                2
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}
