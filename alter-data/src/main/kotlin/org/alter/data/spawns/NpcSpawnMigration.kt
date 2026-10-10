package org.alter.data.spawns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Converts schema-1 region files (`source: "manual"` or `{ page, map }`, no ids) to schema 2, once:
 * `./gradlew :alter-data:spawnSync -PspawnArgs="--migrate"`. Offline; reads and writes only the spawn directory.
 *
 * - a manual entry becomes `source.kind: "manual"` with the minted id `SpawnIds.minted(npc, x, z, height, "schema1")`;
 * - a wiki entry becomes `source.kind: "wiki"` with its wiki id `SpawnIds.wiki(page, npc, x, z, height)`;
 * - an entry with `origin` is rejected: it is not migrated automatically and has to be converted by hand;
 * - schema-2 files are read as they are, so a second run finds nothing to do ("already schema 2") and writes nothing.
 *
 * Every other schema-1 field is copied unchanged. Any problem rejects the whole run with nothing written.
 */
object NpcSpawnMigration {
    const val TOOL = "spawn-migrate"
    private val mapper = ObjectMapper()
    private val V1_FIELDS = setOf("npc", "x", "z", "height", "walkRadius", "direction", "source", "origin", "note")

    sealed interface Result {
        val report: Report

        /** Every file was converted and written. */
        data class Migrated(val files: Int, val manual: Int, val wiki: Int, val write: NpcSpawnFiles.WriteResult, override val report: Report) : Result

        /** Every file already is schema 2 (or there are none); nothing was written. */
        data class AlreadyMigrated(override val report: Report) : Result

        /** Nothing was written: [reason] says why. */
        data class Rejected(val reason: String, override val report: Report) : Result
    }

    fun run(dir: Path): Result {
        val report = ReportBuilder(TOOL)
        fun reject(reason: String): Result {
            report.add("Migration rejected", reason, Report.Severity.ERROR)
            return Result.Rejected(reason, report.build())
        }
        if (!Files.isDirectory(dir)) return reject("$dir does not exist.")

        val errors = mutableListOf<String>()
        val files = mutableListOf<NpcSpawnFile>()
        var migratedFiles = 0
        NpcSpawnFiles.jsonFiles(dir).forEach { path ->
            val name = path.fileName.toString()
            val text = Files.readString(path)
            val schema = runCatching { mapper.readTree(text)?.get("schemaVersion") }.getOrNull()
            if (schema != null && schema.isInt && schema.intValue() == 1) {
                convert(name, text, errors)?.let {
                    files += it
                    migratedFiles++
                }
            } else {
                NpcSpawnFiles.parse(name, text, errors)?.let { files += it }
            }
        }
        errors += NpcSpawnFiles.crossFileErrors(files)
        report.summary("files", files.size)
        report.summary("migratedFiles", migratedFiles)
        errors.forEach { report.add("Problems", it, Report.Severity.ERROR) }
        if (errors.isNotEmpty()) return reject("${errors.size} problem(s); no file was migrated.")
        if (migratedFiles == 0) {
            report.add("Nothing to do", "already schema 2", Report.Severity.INFO)
            return Result.AlreadyMigrated(report.build())
        }

        // Never write a file the reader would reject.
        val invalid = mutableListOf<String>()
        files.forEach { NpcSpawnFiles.parse("${it.regionId}.json", NpcSpawnFiles.render(it), invalid) }
        if (invalid.isNotEmpty()) {
            invalid.forEach { report.add("Migrated entries the reader rejects", it, Report.Severity.ERROR) }
            return reject("${invalid.size} problem(s) after migrating; no file was written.")
        }
        val entries = files.flatMap { it.spawns }
        val manual = entries.count { it.source is NpcSpawnSource.Manual }
        val wiki = entries.count { it.source is NpcSpawnSource.Wiki }
        report.summary("manual", manual)
        report.summary("edit", entries.count { it.source is NpcSpawnSource.Edit })
        report.summary("wiki", wiki)
        report.summary("entries", entries.size)
        val written = NpcSpawnFiles.write(dir, files)
        report.summary("filesWritten", written.written.size)
        report.summary("filesRemoved", written.removed.size)
        return Result.Migrated(files.size, manual, wiki, written, report.build())
    }

    /** One schema-1 file as schema 2, or null with its problems in [errors]. */
    fun convert(fileName: String, text: String, errors: MutableList<String>): NpcSpawnFile? {
        val before = errors.size
        fun err(message: String) {
            errors += "$fileName: $message"
        }
        val root = runCatching { mapper.readTree(text) }.getOrNull() as? ObjectNode
        val regionId = root?.get("regionId")?.takeIf { it.isInt }?.intValue()
        val spawns = root?.get("spawns")?.takeIf { it.isArray }
        if (root == null || regionId == null || spawns == null) {
            err("not a schema-1 region file")
            return null
        }
        val entries = spawns.mapIndexedNotNull { i, node -> convertEntry(node) { err("spawns[$i]: $it") } }
        if (errors.size > before) return null
        return NpcSpawnFile(regionId, entries)
    }

    private fun convertEntry(node: JsonNode, err: (String) -> Unit): NpcSpawnEntry? {
        if (node !is ObjectNode) return null.also { err("must be an object") }
        node.fieldNames().asSequence().filter { it !in V1_FIELDS }.forEach { err("unknown field '$it'") }
        if (node.has("origin")) return null.also { err("origin is not migrated automatically; convert by hand") }
        val npc = node.get("npc")?.takeIf { it.isTextual }?.textValue()
        fun int(field: String) = node.get(field)?.takeIf { it.isInt }?.intValue()
        val x = int("x")
        val z = int("z")
        val height = int("height")
        val walkRadius = int("walkRadius")
        if (npc == null || x == null || z == null || height == null || walkRadius == null) {
            return null.also { err("npc, x, z, height and walkRadius are required") }
        }
        val direction = node.get("direction")?.textValue()
        val note = node.get("note")?.textValue()
        val source = node.get("source")
        return when {
            source != null && source.isTextual && source.textValue() == NpcSpawnFiles.KIND_MANUAL ->
                NpcSpawnEntry(SpawnIds.minted(npc, x, z, height, SpawnIds.MIGRATION_SALT), npc, x, z, height, walkRadius, direction, NpcSpawnSource.Manual, note)
            source is ObjectNode && source.get("page")?.isTextual == true && source.get("map")?.isTextual == true -> {
                val page = source.get("page").textValue()
                NpcSpawnEntry(SpawnIds.wiki(page, npc, x, z, height), npc, x, z, height, walkRadius, direction, NpcSpawnSource.Wiki(page, source.get("map").textValue()), note)
            }
            else -> null.also { err("source must be \"manual\" or { \"page\", \"map\" }") }
        }
    }
}
