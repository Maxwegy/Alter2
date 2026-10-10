package org.alter.data.spawns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes the per-region NPC spawn files, `data/cfg/spawns/npcs/<regionId>.json` (schema 2).
 *
 * Reading never throws: every problem in every file is collected so the boot loader can report them all at
 * once. Writing is deterministic (fixed field order, canonical entry order, two-space indent, `\n`, trailing
 * newline), rewrites only files whose content changed and deletes region files that end up empty.
 * Schema-1 files are only read by [NpcSpawnMigration].
 */
object NpcSpawnFiles {
    const val SCHEMA_VERSION = 2
    const val WIKI_PAGE_PREFIX = "https://oldschool.runescape.wiki/w/"
    const val KIND_MANUAL = "manual"
    const val KIND_EDIT = "edit"
    const val KIND_WIKI = "wiki"

    /** Engine tiles pack x and z into 15 bits each, and there are four planes. */
    const val MAX_COORDINATE = 0x7FFF
    const val MAX_HEIGHT = 3

    internal val FILE_NAME = Regex("""(\d+)\.json""")
    private val TOP_LEVEL_FIELDS = setOf("schemaVersion", "regionId", "spawns")
    private val ENTRY_FIELDS = setOf("id", "npc", "x", "z", "height", "walkRadius", "direction", "source", "note")
    private val SOURCE_FIELDS = linkedMapOf(
        KIND_WIKI to setOf("kind", "page", "map", "mapId", "rule"),
        KIND_MANUAL to setOf("kind"),
        KIND_EDIT to setOf("kind", "at", "page", "map"),
    )
    private val mapper = ObjectMapper()

    private fun kindRank(source: NpcSpawnSource): Int = when (source) {
        NpcSpawnSource.Manual -> 0
        is NpcSpawnSource.Edit -> 1
        is NpcSpawnSource.Wiki -> 2
    }

    private fun page(source: NpcSpawnSource): String? = when (source) {
        NpcSpawnSource.Manual -> null
        is NpcSpawnSource.Edit -> source.page
        is NpcSpawnSource.Wiki -> source.page
    }

    private fun map(source: NpcSpawnSource): String? = when (source) {
        NpcSpawnSource.Manual -> null
        is NpcSpawnSource.Edit -> source.map
        is NpcSpawnSource.Wiki -> source.map
    }

    /** x, z, height, npc, then manual < edit < wiki, then page, map and id, so the order is total. */
    val canonicalOrder: Comparator<NpcSpawnEntry> = compareBy<NpcSpawnEntry>({ it.x }, { it.z }, { it.height }, { it.npc })
        .thenBy { kindRank(it.source) }
        .thenBy { page(it.source) }
        .thenBy { map(it.source) }
        .thenBy { it.id }

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
        errors += crossFileErrors(files)
        return ReadResult.Read(files, errors)
    }

    /** Duplicate (npc, tile) keys and duplicate ids across [files]. */
    fun crossFileErrors(files: List<NpcSpawnFile>): List<String> {
        val errors = mutableListOf<String>()
        val all = files.flatMap { file -> file.spawns.map { file.regionId to it } }
        all.groupBy { (_, entry) -> entry.key }
            .filterValues { it.size > 1 }
            .forEach { (key, dupes) ->
                errors += "duplicate spawn ${key.npc} at (${key.x}, ${key.z}, ${key.height}) in ${dupes.map { "${it.first}.json" }.distinct().joinToString()}"
            }
        all.groupBy { (_, entry) -> entry.id }
            .filterValues { it.size > 1 }
            .forEach { (id, dupes) ->
                errors += "duplicate id $id in ${dupes.map { "${it.first}.json" }.distinct().joinToString()}"
            }
        return errors
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
        val schema = root.get("schemaVersion")
        if (schema != null && schema.isInt && schema.intValue() < SCHEMA_VERSION) {
            err("schemaVersion ${schema.intValue()} is no longer read; convert it with ./gradlew :alter-data:spawnSync -PspawnArgs=\"--migrate\"")
            return null
        }
        if (schema == null || !schema.isInt || schema.intValue() != SCHEMA_VERSION) err("schemaVersion must be $SCHEMA_VERSION")
        unknownFields(root, TOP_LEVEL_FIELDS).forEach { err("unknown field '$it'") }
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
        val f = Failures(err)
        unknownFields(node, ENTRY_FIELDS).forEach {
            f.fail(if (it == "origin") ORIGIN_REMOVED else "unknown field '$it'")
        }
        val id = node.get("id")?.takeIf { it.isTextual }?.textValue()
        if (id == null || !SpawnIds.isValid(id)) f.fail("id must match ${SpawnIds.PATTERN.pattern}")
        val npc = node.get("npc")?.takeIf { it.isTextual }?.textValue()
        if (npc == null || !npc.startsWith("npc.") || npc.length == 4) f.fail("npc must be an RSCM name like \"npc.hans\"")
        fun int(field: String, range: IntRange): Int? {
            val value = node.get(field)?.takeIf { it.isInt }?.intValue()
            if (value == null || value !in range) f.fail("$field must be an integer in ${range.first}..${range.last}")
            return value
        }
        val x = int("x", 0..MAX_COORDINATE)
        val z = int("z", 0..MAX_COORDINATE)
        val height = int("height", 0..MAX_HEIGHT)
        val walkRadius = int("walkRadius", 0..Int.MAX_VALUE)
        val direction = optionalText(node, "direction", f)
        val note = optionalText(node, "note", f)
        val source = parseSource(node.get("source"), f)
        if (source is NpcSpawnSource.Wiki && id != null && !id.startsWith(SpawnIds.WIKI_PREFIX)) {
            f.fail("a wiki entry's id must start with '${SpawnIds.WIKI_PREFIX}'")
        }
        if (f.failed || source == null) return null
        return NpcSpawnEntry(id!!, npc!!, x!!, z!!, height!!, walkRadius!!, direction, source, note)
    }

    private fun optionalText(node: ObjectNode, field: String, f: Failures, prefix: String = ""): String? {
        val value = node.get(field) ?: return null
        if (!value.isTextual || value.textValue().isBlank()) {
            f.fail("$prefix$field must be a non-empty string when present")
            return null
        }
        return value.textValue()
    }

    private fun parseSource(node: JsonNode?, f: Failures): NpcSpawnSource? {
        if (node !is ObjectNode) {
            f.fail("source must be an object with a kind (${SOURCE_FIELDS.keys.joinToString()})")
            return null
        }
        val kind = node.get("kind")?.takeIf { it.isTextual }?.textValue()
        val known = SOURCE_FIELDS[kind]
        if (kind == null || known == null) {
            f.fail("source.kind must be one of ${SOURCE_FIELDS.keys.joinToString()}")
            return null
        }
        val before = f.count
        unknownFields(node, known).forEach { f.fail("unknown source field '$it'") }
        val page = optionalText(node, "page", f, "source.")
        val map = optionalText(node, "map", f, "source.")
        if (page != null && (!page.startsWith(WIKI_PAGE_PREFIX) || page.length == WIKI_PAGE_PREFIX.length)) {
            f.fail("source.page must be a URL starting with $WIKI_PAGE_PREFIX")
        }
        if (map != null && !map.startsWith("{{Map")) f.fail("source.map must be the verbatim {{Map|...}} template")
        val source = when (kind) {
            KIND_MANUAL -> NpcSpawnSource.Manual
            KIND_EDIT -> {
                val at = optionalText(node, "at", f, "source.")
                if (at == null && node.get("at") == null) f.fail("source.at must be the instant the edit was made")
                if ((page == null) != (map == null)) f.fail("source.page and source.map go together on an edit entry")
                at?.let { NpcSpawnSource.Edit(it, page, map) }
            }
            else -> {
                if (page == null && node.get("page") == null) f.fail("source.page must be a URL starting with $WIKI_PAGE_PREFIX")
                if (map == null && node.get("map") == null) f.fail("source.map must be the verbatim {{Map|...}} template")
                val mapId = node.get("mapId")?.let { v -> if (v.isInt) v.intValue() else null.also { f.fail("source.mapId must be an integer when present") } }
                val rule = optionalText(node, "rule", f, "source.")
                if (page != null && map != null) NpcSpawnSource.Wiki(page, map, rule, mapId) else null
            }
        }
        return if (f.count > before) null else source
    }

    /** Forwards failures and counts them. */
    private class Failures(private val sink: (String) -> Unit) {
        var count = 0
            private set
        val failed: Boolean get() = count > 0

        fun fail(message: String) {
            count++
            sink(message)
        }
    }

    private const val ORIGIN_REMOVED =
        "'origin' was removed in schema 2; an edited wiki entry has source.kind \"edit\" with its former page and map"

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
        fields += "\"id\": ${quote(e.id)}"
        fields += "\"npc\": ${quote(e.npc)}"
        fields += "\"x\": ${e.x}"
        fields += "\"z\": ${e.z}"
        fields += "\"height\": ${e.height}"
        fields += "\"walkRadius\": ${e.walkRadius}"
        e.direction?.let { fields += "\"direction\": ${quote(it)}" }
        val source = mutableListOf("\"kind\": ${quote(e.source.kind)}")
        when (val s = e.source) {
            NpcSpawnSource.Manual -> Unit
            is NpcSpawnSource.Edit -> {
                source += "\"at\": ${quote(s.at)}"
                s.page?.let { source += "\"page\": ${quote(it)}" }
                s.map?.let { source += "\"map\": ${quote(it)}" }
            }
            is NpcSpawnSource.Wiki -> {
                source += "\"page\": ${quote(s.page)}"
                source += "\"map\": ${quote(s.map)}"
                s.mapId?.let { source += "\"mapId\": $it" }
                s.rule?.let { source += "\"rule\": ${quote(it)}" }
            }
        }
        fields += "\"source\": {\n" + source.joinToString(",\n") { "        $it" } + "\n      }"
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

    internal fun jsonFiles(dir: Path): List<Path> = Files.list(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
    }.sortedBy { it.fileName.toString() }
}
