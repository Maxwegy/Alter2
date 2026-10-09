package dev.openrune.cache.tools.staging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import dev.openrune.cache.CacheManager
import gg.rsmod.util.Namer
import org.alter.data.report.Report
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `./gradlew :plugins:tools:cacheDryRun -PcacheArgs="<staged dir> <build>"`
 *
 * Decodes a staged cache with our own decoders and reports what would break if we switched to it:
 * decoder failures, unknown opcodes, and RSCM names (what content refers to) that would disappear or
 * change id compared to the committed `data/cfg/rscm`. Runs in its own JVM: CacheManager is a singleton.
 * Exit codes: 0 clean, 2 problems found, 1 error.
 */
fun main(args: Array<String>) {
    val dataDir = Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize()
    val exitCode = try {
        val dir = Paths.get(args.getOrNull(0) ?: error("Usage: cacheDryRun <staged cache dir> <build>"))
        val build = args.getOrNull(1)?.toInt() ?: error("Usage: cacheDryRun <staged cache dir> <build>")
        dryRun(dir, build, dataDir)
    } catch (e: Exception) {
        e.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private class DecodeWarnings : AppenderBase<ILoggingEvent>() {
    val messages = mutableListOf<String>()

    override fun append(event: ILoggingEvent) {
        val message = event.formattedMessage
        if (message.contains("Unable to decode") || message.contains("Unknown opcode", ignoreCase = true)) {
            messages += "${event.loggerName.substringAfterLast('.')}: $message"
        }
    }
}

private fun dryRun(dir: Path, build: Int, dataDir: Path): Int {
    val warnings = DecodeWarnings()
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    warnings.context = LoggerFactory.getILoggerFactory() as LoggerContext
    warnings.start()
    root.addAppender(warnings)

    val failures = mutableListOf<String>()
    try {
        CacheManager.init(dir, build)
    } catch (e: Exception) {
        failures += "CacheManager.init failed: ${e::class.simpleName}: ${e.message}"
    }

    val committed = listOf("item", "npc", "object").associateWith { table -> readRscm(dataDir.resolve("cfg/rscm/$table.rscm")) }
    val staged: Map<String, Map<Int, String>> = if (failures.isEmpty()) {
        mapOf(
            "item" to CacheManager.getItems().mapValues { (_, item) ->
                if (item.noteTemplateId > 0) CacheManager.getItemOrDefault(item.noteLinkId).name + "_NOTED" else item.name
            },
            "npc" to CacheManager.getNpcs().mapValues { it.value.name },
            "object" to CacheManager.getObjects().mapValues { it.value.name ?: "" },
        )
    } else {
        emptyMap()
    }

    val sections = mutableListOf<Report.Section>()
    sections += Report.Section("Decoder failures", failures, Report.Severity.ERROR)
    sections += Report.Section("Unknown opcodes (decoded but misread)", warnings.messages.distinct().sorted(), Report.Severity.ERROR)
    var removed = 0
    var moved = 0
    committed.forEach { (table, names) ->
        val entities = staged[table].orEmpty()
        // Compare by id: does each committed name's id still exist, and does it still hold the same thing?
        val gone = names.filter { (_, id) -> id !in entities }.keys.sorted()
        val changed = names.mapNotNull { (name, id) ->
            val now = entities[id] ?: return@mapNotNull null
            val current = sanitize(now)
            if (current == null || current == name || current == baseName(name)) null else "$table.$name ($id) is now '$now'"
        }.sorted()
        removed += gone.size
        moved += changed.size
        sections += Report.Section("$table names whose id no longer exists", gone.map { "$table.$it" }, Report.Severity.WARNING)
        sections += Report.Section("$table names whose id now holds something else", changed, Report.Severity.WARNING)
    }

    val report = Report(
        tool = "cache-dryrun-$build",
        summary = mapOf(
            "dir" to dir.toString(),
            "build" to build,
            "items" to CacheManager.itemSize(),
            "npcs" to CacheManager.npcSize(),
            "objects" to CacheManager.objectSize(),
            "decoderFailures" to failures.size,
            "unknownOpcodeWarnings" to warnings.messages.size,
            "rscmNamesRemoved" to removed,
            "rscmNamesChangedId" to moved,
        ),
        sections = sections,
    )
    println("Report: ${report.write(dataDir.resolve("reports"))}")
    report.summary.forEach { (key, value) -> println("  $key: $value") }
    return if (failures.isEmpty() && warnings.messages.isEmpty() && removed == 0 && moved == 0) 0 else 2
}

/** The RSCM base name for [raw], using the same sanitizing as [Namer] (lowercase, `_` for spaces, no tags). */
private fun sanitize(raw: String): String? {
    val probe = Namer()
    return probe.name(raw, 0)?.lowercase()
}

/** A committed RSCM name without the `_<id>` suffix Namer adds to duplicates. */
private fun baseName(name: String): String = name.replace(Regex("_\\d+$"), "")

private fun readRscm(file: Path): Map<String, Int> {
    if (!Files.exists(file)) return emptyMap()
    return Files.readAllLines(file).mapNotNull { line ->
        val parts = line.split(':')
        if (parts.size == 2) parts[1].trim().toIntOrNull()?.let { parts[0].trim() to it } else null
    }.toMap()
}
