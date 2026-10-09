package dev.openrune.cache.tools.staging

import dev.openrune.cache.filestore.Cache
import org.alter.data.report.Report
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `./gradlew :plugins:tools:gamevalDump -PcacheArgs="<cache dir> [<out dir>]"`
 *
 * Reads the gameval name tables (index 24, revision 241+) of a cache and writes one `<kind>.txt` per kind
 * (`id<TAB>name`, sorted by id) under `data/reports/gameval/` or the given out dir, plus a report that counts
 * each kind and compares the item/npc/object tables with the committed `data/cfg/rscm` names: how many of our
 * RSCM ids the cache names, and the first names that differ. This is the foundation for generating RSCM from
 * the cache instead of from decoded definitions. Exit codes: 0 written, 2 no gameval index, 1 error.
 */
fun main(args: Array<String>) {
    val dataDir = Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize()
    val exitCode = try {
        val dir = Paths.get(args.getOrNull(0) ?: error("Usage: gamevalDump <cache dir> [<out dir>]"))
        val out = args.getOrNull(1)?.let { Paths.get(it) } ?: dataDir.resolve("reports/gameval")
        dump(dir, out, dataDir)
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun dump(dir: Path, out: Path, dataDir: Path): Int {
    val cache = Cache.load(dir, false)
    try {
        if (!Gameval.isPresent(cache)) {
            println("No gameval tables: $dir has no index ${Gameval.INDEX} (revisions before 241 don't ship names)")
            return 2
        }
        Files.createDirectories(out)
        val tables = Gameval.readAll(cache)
        val summary = linkedMapOf<String, Any>("dir" to dir.toString(), "out" to out.toString())
        val sections = mutableListOf<Report.Section>()
        for ((kind, names) in tables) {
            val file = out.resolve("${kind.name.lowercase()}.txt")
            Files.write(file, names.entries.sortedBy { it.key }.map { "${it.key}\t${it.value}" })
            summary[kind.name.lowercase()] = names.size
            val rscm = kind.rscm?.let { dataDir.resolve("cfg/rscm/$it.rscm") }?.takeIf(Files::exists) ?: continue
            val committed = readRscmNames(rscm)
            val named = committed.keys.count { it in names }
            val differing = committed.entries.asSequence()
                .filter { (id, name) -> names[id] != null && names[id] != name }
                .map { (id, name) -> "$id: rscm `$name` vs gameval `${names[id]}`" }
                .take(25).toList()
            summary["${kind.name.lowercase()}RscmIdsNamed"] = "$named/${committed.size}"
            sections += Report.Section("${kind.name} vs data/cfg/rscm/${kind.rscm}.rscm (first differing names)", differing, Report.Severity.INFO)
        }
        val report = Report(tool = "gameval-dump", summary = summary, sections = sections)
        println("Report: ${report.write(dataDir.resolve("reports"))}")
        tables.forEach { (kind, names) -> println("${kind.name.lowercase().padEnd(10)} ${names.size}") }
        return 0
    } finally {
        cache.close()
    }
}

/** Lines of a committed `data/cfg/rscm/<table>.rscm` file are `name:id`. */
private fun readRscmNames(file: Path): Map<Int, String> =
    Files.readAllLines(file).asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && ':' in it }
        .associate { line -> line.substringAfterLast(':').trim().toInt() to line.substringBeforeLast(':').trim() }
