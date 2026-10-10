package dev.openrune.cache.tools.rscm

import gg.rsmod.util.BuildInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * `./gradlew :plugins:tools:rscmGenerate -PcacheArgs="<cache dir> <build> [--out <dir>] [--previous <rscm dir>]
 *   [--previous-cache <dir>] [--previous-build N] [--overrides <file>] [--refs <dir,dir>] [--commit]"`
 *
 * Generates RSCM tables for a staged cache from its gameval names (index 24), with aliases for every committed
 * name, and a migration report. Defaults: previous tables `../data/cfg/rscm`, previous cache the staged
 * `data/cache-staging/<BuildInfo.REVISION>-*` when present, overrides `../data/cfg/rscm-migrations/overrides.json`,
 * references scanned in `game-plugins/src` and `data/cfg`, output `<cache dir>/rscm-out/`. `--commit` copies the
 * tables into `data/cfg` and is refused while a referenced name is unresolved or clashes.
 * Exit codes: 0 clean, 2 blocking entries (report written, nothing committed), 1 error.
 */
fun main(args: Array<String>) {
    val dataDir = Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize()
    val exitCode = try {
        val usage = "Usage: rscmGenerate <cache dir> <build> [--out <dir>] [--previous <rscm dir>] [--previous-cache <dir>] [--previous-build N] [--overrides <file>] [--refs <dir,dir>] [--commit]"
        val cacheDir = Paths.get(args.getOrNull(0) ?: error(usage)).normalize()
        val build = args.getOrNull(1)?.toIntOrNull() ?: error(usage)
        fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val previousBuild = option("--previous-build")?.toInt() ?: BuildInfo.REVISION
        val previousCache = option("--previous-cache")?.let { Paths.get(it) } ?: stagedCacheFor(dataDir, previousBuild)
        val repoRoot = dataDir.toAbsolutePath().parent
        val generator = RscmGenerate(
            cacheDir = cacheDir,
            build = build,
            previousRscmDir = option("--previous")?.let { Paths.get(it) } ?: dataDir.resolve("cfg/rscm"),
            previousCacheDir = previousCache,
            previousBuild = previousBuild,
            overridesFile = option("--overrides")?.let { Paths.get(it) } ?: dataDir.resolve("cfg/rscm-migrations/overrides.json"),
            referenceRoots = option("--refs")?.split(',')?.map { Paths.get(it) } ?: listOf(repoRoot.resolve("game-plugins/src"), dataDir.resolve("cfg")),
            out = option("--out")?.let { Paths.get(it) } ?: cacheDir.resolve("rscm-out"),
        )
        println("Previous cache for display names: ${previousCache ?: "none (using committed names)"}")
        val generated = generator.run()
        val report = generator.report(generated)
        println("Report: ${report.write(dataDir.resolve("reports"))}")
        println("Migration summary: ${generated.out.resolve("migration.md")}")
        generated.migrations.forEach { (table, m) ->
            println("${table.rscm.padEnd(7)} canonical=${m.canonical.size} aliases=${m.aliases.size} remapped=${m.count(Outcome.REMAPPED)} unresolved=${m.count(Outcome.UNRESOLVED)} clash=${m.count(Outcome.CLASH)} overridden=${m.count(Outcome.OVERRIDDEN)} blocking=${m.blocking.size}")
        }
        if (generated.blocking.isNotEmpty()) {
            println("${generated.blocking.size} referenced names need a decision in overrides.json:")
            generated.blocking.forEach { println("  ${it.table}.${it.oldName} (${it.oldId}): ${it.outcome} ${it.note}") }
            2
        } else {
            if ("--commit" in args) {
                generator.commit(generated, dataDir)
                println("Committed tables into ${dataDir.resolve("cfg")}")
            }
            0
        }
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun stagedCacheFor(dataDir: Path, build: Int): Path? {
    val staging = dataDir.resolve("cache-staging")
    if (!Files.isDirectory(staging)) return null
    return Files.list(staging).use { dirs -> dirs.filter { it.name.startsWith("$build-") && Files.exists(it.resolve("main_file_cache.idx255")) }.sorted().findFirst().orElse(null) }
}
