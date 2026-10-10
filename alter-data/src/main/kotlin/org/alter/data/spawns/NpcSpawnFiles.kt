package org.alter.data.spawns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes the per-region NPC spawn files, `data/cfg/spawns/npcs/<regionId>.json`.
 *
 * Reading never throws: every problem in every file is collected so the boot loader can report them all at
 * once. Writing is deterministic (fixed field order, canonical entry order, two-space indent, `\n`, trailing
 * newline), rewrites only files whose content changed and deletes region files that end up empty.
 */
object NpcSpawnFiles {
    const val SCHEMA_VERSION = 1
    const val WIKI_PAGE_PREFIX = "https://oldschool.runescape.wiki/w/"
    const val MANUAL = "manual"

    /** Engine tiles pack x and z into 15 bits each, and there are four planes. */
    const val MAX_COORDINATE = 0x7FFF
    const val MAX_HEIGHT = 3

    private val FILE_NAME = Regex("""(\d+)\.json""")
    private val TOP_LEVEL_FIELDS = setOf("schemaVersion", "regionId", "spawns")
    private val ENTRY_FIELDS = setOf("npc", "x", "z", "height", "walkRadius", "direction", "source", "origin", "note")
    private val WIKI_SOURCE_FIELDS = setOf("page", "map")
    private val mapper = ObjectMapper()

    /** x, z, height, npc, then manual before wiki (and wiki by page, then map, so the order is total). */
    val canonicalOrder: Comparator<NpcSpawnEntry> = compareBy<NpcSpawnEntry>({ it.x }, { it.z }, { it.height }, { it.npc })
        .thenBy { if (it.source is NpcSpawnSource.Manual) 0 else 1 }
        .thenBy { (it.source as? NpcSpawnSource.Wiki)?.page }
        .thenBy { (it.source as? NpcSpawnSource.Wiki)?.map }

    sealed interface ReadResult {
        /** The directory does not exist. */
        data object Missing : ReadResult

        /** [files] holds every file that parsed; [errors] is empty when the whole directory is valid. */
        data class Read(val files: List<NpcSpawnFile>, val errors: List<String>) : ReadResult {
            val entries: List<NpcSpawnEntry> get() = files.flatMap { it.spawns }
        }
    }

    data class WriteResult(val written: List<String>, val removed: List<String>)

    /** Reads every `*.json` in [dir] (other files, such as the README, are ignored). */
    fun readAll(dir: Path): ReadResult {
        if (!Files.isDirectory(dir)) return ReadResult.Missing
        val errors = mutableListOf<String>()
        val files = jsonFiles(dir).mapNotNull { file ->
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                errors += "${file.fileName}: cannot read: ${e.message}"
                return@mapNotNull null
            }
            parse(file.fileName.toString(), text, errors)
        }
        files.flatMap { file -> file.spawns.map { file.regionId to it } }
            .groupBy { (_, entry) -> entry.key }
            .filterValues { it.size > 1 }
            .forEach { (key, dupes) ->
                errors += "duplicate spawn ${key.npc} at (${key.x}, ${key.z}, ${key.height}) in ${dupes.map { "${it.first}.json" }.distinct().joinToString()}"
            }
        return ReadResult.Read(files, errors)
    }

    /** Parses one file named [fileName]; problems go to [errors] and make it return null. */
    fun parse(fileName: String, text: String, errors: MutableList<String>): NpcSpawnFile? {
        val before = errors.size
        fun err(message: String) {
            errors += "$fileName: $message"
        }
        val nameRegion = FILE_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()
        if (nameRegion == null) err("file name must be <regionId>.json")
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            err("not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            return null
        }
        if (root !is ObjectNode) {
            err("top level must be an object")
            return null
        }
        unknownFields(root, TOP_LEVEL_FIELDS).forEach { err("unknown field '$it'") }
        val schema = root.get("schemaVersion")
        if (schema == null || !schema.isInt || schema.intValue() != SCHEMA_VERSION) err("schemaVersion must be $SCHEMA_VERSION")
        val regionNode = root.get("regionId")
        val regionId = regionNode?.takeIf { it.isInt }?.intValue()
        if (regionId == null) err("regionId must be an integer")
        if (regionId != null && nameRegion != null && regionId != nameRegion) err("regionId $regionId does not match the file name")
        val spawnsNode = root.get("spawns")
        if (spawnsNode == null || !spawnsNode.isArray) {
            err("spawns must be an array")
            return null
        }
        val spawns = spawnsNode.mapIndexedNotNull { i, node -> parseEntry(node) { err("spawns[$i]: $it") } }
        if (regionId != null) {
            spawns.forEachIndexed { i, e ->
                if (e.regionId != regionId) err("spawns[$i]: ${e.npc} at (${e.x}, ${e.z}) is in region ${e.regionId}, not $regionId")
            }
        }
        if (errors.size > before || regionId == null) return null
        return NpcSpawnFile(regionId, spawns, SCHEMA_VERSION)
    }

    private fun parseEntry(node: JsonNode, err: (String) -> Unit): NpcSpawnEntry? {
        if (node !is ObjectNode) {
            err("must be an object")
            return null
        }
        var ok = true
        fun fail(message: String) {
            ok = false
            err(message)
        }
        unknownFields(node, ENTRY_FIELDS).forEach { fail("unknown field '$it'") }
        val npc = node.get("npc")?.takeIf { it.isTextual }?.textValue()
        if (npc == null || !npc.startsWith("npc.") || npc.length == 4) fail("npc must be an RSCM name like \"npc.hans\"")
        fun int(field: String, range: IntRange): Int? {
            val value = node.get(field)?.takeIf { it.isInt }?.intValue()
            if (value == null || value !in range) fail("$field must be an integer in ${range.first}..${range.last}")
            return value
        }
        val x = int("x", 0..MAX_COORDINATE)
        val z = int("z", 0..MAX_COORDINATE)
        val height = int("height", 0..MAX_HEIGHT)
        val walkRadius = int("walkRadius", 0..Int.MAX_VALUE)
        fun optionalText(field: String): String? {
            val value = node.get(field) ?: return null
            if (!value.isTextual || value.textValue().isBlank()) fail("$field must be a non-empty string when present")
            return value.textValue()
        }
        val direction = optionalText("direction")
        val origin = optionalText("origin")
        val note = optionalText("note")
        val source = parseSource(node.get("source"), ::fail)
        if (!ok || source == null) return null
        return NpcSpawnEntry(npc!!, x!!, z!!, height!!, walkRadius!!, direction, source, origin, note)
    }

    private fun parseSource(node: JsonNode?, fail: (String) -> Unit): NpcSpawnSource? {
        if (node != null && node.isTextual && node.textValue() == MANUAL) return NpcSpawnSource.Manual
        if (node !is ObjectNode) {
            fail("source must be \"$MANUAL\" or { \"page\", \"map\" }")
            return null
        }
        unknownFields(node, WIKI_SOURCE_FIELDS).forEach { fail("unknown source field '$it'") }
        val page = node.get("page")?.takeIf { it.isTextual }?.textValue()
        val map = node.get("map")?.takeIf { it.isTextual }?.textValue()
        if (page == null || !page.startsWith(WIKI_PAGE_PREFIX) || page.length == WIKI_PAGE_PREFIX.length) {
            fail("source.page must be a URL starting with $WIKI_PAGE_PREFIX")
            return null
        }
        if (map == null || !map.startsWith("{{Map")) {
            fail("source.map must be the verbatim {{Map|...}} template")
            return null
        }
        return NpcSpawnSource.Wiki(page, map)
    }

    private fun unknownFields(node: ObjectNode, known: Set<String>): List<String> =
        node.fieldNames().asSequence().filter { it !in known }.toList()

    /** Groups [entries] into one file per region, regions ascending. */
    fun group(entries: Collection<NpcSpawnEntry>): List<NpcSpawnFile> =
        entries.groupBy { it.regionId }.toSortedMap().map { (region, spawns) -> NpcSpawnFile(region, spawns) }

    /** The exact bytes of [file]'s JSON, with its entries in [canonicalOrder]. */
    fun render(file: NpcSpawnFile): String {
        require(file.spawns.all { it.regionId == file.regionId }) { "an entry outside region ${file.regionId}" }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(file.schemaVersion).append(",\n")
        sb.append("  \"regionId\": ").append(file.regionId).append(",\n")
        if (file.spawns.isEmpty()) {
            sb.append("  \"spawns\": []\n")
        } else {
            sb.append("  \"spawns\": [\n")
            file.spawns.sortedWith(canonicalOrder).forEachIndexed { i, e ->
                if (i > 0) sb.append(",\n")
                renderEntry(sb, e)
            }
            sb.append("\n  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderEntry(sb: StringBuilder, e: NpcSpawnEntry) {
        val fields = mutableListOf<String>()
        fields += "\"npc\": ${quote(e.npc)}"
        fields += "\"x\": ${e.x}"
        fields += "\"z\": ${e.z}"
        fields += "\"height\": ${e.height}"
        fields += "\"walkRadius\": ${e.walkRadius}"
        e.direction?.let { fields += "\"direction\": ${quote(it)}" }
        fields += when (val s = e.source) {
            NpcSpawnSource.Manual -> "\"source\": ${quote(MANUAL)}"
            is NpcSpawnSource.Wiki -> "\"source\": {\n        \"page\": ${quote(s.page)},\n        \"map\": ${quote(s.map)}\n      }"
        }
        e.origin?.let { fields += "\"origin\": ${quote(it)}" }
        e.note?.let { fields += "\"note\": ${quote(it)}" }
        sb.append("    {\n")
        sb.append(fields.joinToString(",\n") { "      $it" })
        sb.append("\n    }")
    }

    private fun quote(s: String): String = mapper.writeValueAsString(s)

    /**
     * Makes [dir] hold exactly [files]: changed files are rewritten atomically, unchanged ones are left alone,
     * and region files that are empty or absent from [files] are deleted. Other files (the README) are kept.
     */
    fun write(dir: Path, files: Collection<NpcSpawnFile>): WriteResult {
        require(files.map { it.regionId }.toSet().size == files.size) { "two files for the same region" }
        val wanted = files.filter { it.spawns.isNotEmpty() }.associate { "${it.regionId}.json" to render(it) }
        val existing = if (Files.isDirectory(dir)) jsonFiles(dir).filter { FILE_NAME.matches(it.fileName.toString()) } else emptyList()
        val written = wanted.filter { (name, text) ->
            val target = dir.resolve(name)
            val current = if (Files.exists(target)) Files.readString(target).replace("\r\n", "\n") else null
            (current != text).also { changed -> if (changed) AtomicFiles.writeText(target, text) }
        }.keys.sorted()
        val removed = existing.map { it.fileName.toString() }.filter { it !in wanted }.sorted()
        removed.forEach { Files.delete(dir.resolve(it)) }
        return WriteResult(written, removed)
    }

    private fun jsonFiles(dir: Path): List<Path> = Files.list(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
    }.sortedBy { it.fileName.toString() }
}
