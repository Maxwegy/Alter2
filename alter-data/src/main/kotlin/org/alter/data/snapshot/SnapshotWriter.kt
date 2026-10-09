package org.alter.data.snapshot

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Indents structure but writes leaf records on one line: objects nested deeper than [OBJECT_LEVELS] and
 * arrays deeper than [ARRAY_LEVELS] are inline, including their closing bracket.
 */
private class SnapshotPrettyPrinter : DefaultPrettyPrinter {
    constructor() : super() {
        _objectIndenter = DepthLimitedIndenter(OBJECT_LEVELS)
        _arrayIndenter = DepthLimitedIndenter(ARRAY_LEVELS)
    }

    constructor(base: SnapshotPrettyPrinter) : super(base)

    override fun createInstance() = SnapshotPrettyPrinter(this)

    override fun writeEndObject(g: JsonGenerator, nrOfEntries: Int) {
        if (_nesting > OBJECT_LEVELS) {
            _nesting--
            g.writeRaw(if (nrOfEntries > 0) " }" else "}")
        } else {
            super.writeEndObject(g, nrOfEntries)
        }
    }

    override fun writeEndArray(g: JsonGenerator, nrOfValues: Int) {
        if (_nesting > ARRAY_LEVELS) {
            _nesting--
            g.writeRaw(if (nrOfValues > 0) " ]" else "]")
        } else {
            super.writeEndArray(g, nrOfValues)
        }
    }

    private class DepthLimitedIndenter(private val maxLevel: Int) : DefaultIndenter("  ", "\n") {
        override fun writeIndentation(g: JsonGenerator, level: Int) {
            if (level <= maxLevel) super.writeIndentation(g, level) else g.writeRaw(' ')
        }
    }

    companion object {
        const val OBJECT_LEVELS = 3
        const val ARRAY_LEVELS = 4
    }
}

/** Everything a sync produces, before it is written. */
data class Snapshot(val items: List<ItemEntry>, val npcs: List<NpcPage>, val drops: List<DropPage>)

/**
 * Writes the snapshot deterministically: stable key order, sorted entries, `\n` line endings and a trailing
 * newline. Only files whose (LF-normalized) content changed are rewritten, stale files are removed, and the
 * manifest is rewritten only when its content changes, so a no-op sync is a zero diff.
 */
class SnapshotWriter(private val dir: Path) {
    data class Result(val written: List<String>, val removed: List<String>) {
        val changed: Boolean get() = written.isNotEmpty() || removed.isNotEmpty()
    }

    fun write(snapshot: Snapshot, generator: String, cacheRevision: Int, sources: List<String>, rowCounts: Map<String, Int>): Result {
        val files = sortedMapOf<String, String>()
        files["items.json"] = render(ItemsFile(snapshot.items.sortedBy { it.id }))
        slugs(snapshot.npcs.map { it.page }).let { slugs -> snapshot.npcs.forEach { files["npcs/${slugs.getValue(it.page)}.json"] = render(it) } }
        slugs(snapshot.drops.map { it.page }).let { slugs -> snapshot.drops.forEach { files["drops/${slugs.getValue(it.page)}.json"] = render(it) } }
        files["README.md"] = README

        val written = files.filter { (path, content) -> writeIfChanged(dir.resolve(path), content) }.keys.toList()
        val removed = listOf("npcs", "drops").flatMap { sub -> staleFiles(sub, files.keys) }
        removed.forEach { Files.delete(dir.resolve(it)) }

        val manifest = Manifest(
            generator = generator,
            cacheRevision = cacheRevision,
            sources = sources,
            rowCounts = rowCounts.toSortedMap(),
            files = files.mapValues { (_, content) -> sha256(content) }.toSortedMap(),
        )
        val manifestWritten = writeIfChanged(dir.resolve(MANIFEST), render(manifest))
        return Result(if (manifestWritten) written + MANIFEST else written, removed)
    }

    fun render(value: Any): String = mapper.writer(printer).writeValueAsString(value).replace("\r\n", "\n") + "\n"

    private fun writeIfChanged(file: Path, content: String): Boolean {
        if (Files.exists(file) && normalize(Files.readString(file)) == content) return false
        AtomicFiles.writeText(file, content)
        return true
    }

    private fun staleFiles(sub: String, keep: Set<String>): List<String> {
        val folder = dir.resolve(sub)
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { stream ->
            stream.filter { it.extension == "json" }
                .map { dir.relativize(it).invariantSeparatorsPathString }
                .filter { it !in keep }
                .sorted()
                .toList()
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"

        private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

        /** A fresh printer per write: pretty printers are stateful. */
        private val printer get() = SnapshotPrettyPrinter()

        fun normalize(text: String) = text.replace("\r\n", "\n")

        fun sha256(content: String): String =
            MessageDigest.getInstance("SHA-256").digest(normalize(content).toByteArray()).joinToString("") { "%02x".format(it) }

        /** File-name slugs per page; pages whose slugs collide get a short stable hash suffix. */
        fun slugs(pages: List<String>): Map<String, String> {
            val base = pages.associateWith { page -> page.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "page" } }
            val collisions = base.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            return base.mapValues { (page, slug) ->
                if (slug in collisions) "${slug}_${CRC32().apply { update(page.toByteArray()) }.value.toString(16)}" else slug
            }
        }

        val README = """
            |# OSRS Wiki snapshot
            |
            |Generated by `./gradlew :alter-data:wikiSync`. Do not edit these files by hand: changes are
            |overwritten by the next sync. Put corrections in the override folders instead
            |(`data/cfg/npcs/overrides`, `data/cfg/drops/overrides`, `data/cfg/items/itemOverrides`).
            |
            |- `manifest.json`: schema version, the cache revision ids were validated against, row counts, file hashes
            |- `items.json`: equipment bonuses, attack speed and range, combat style
            |- `npcs/`: monster combat stats, one file per wiki page
            |- `drops/`: normalized drop tables (exact chances as `[numerator, denominator]`), one file per wiki page
            |
            |## Source and license
            |
            |Data comes from the Old School RuneScape Wiki (https://oldschool.runescape.wiki) via its Bucket API.
            |Wiki content is licensed under CC BY-NC-SA 3.0 (https://creativecommons.org/licenses/by-nc-sa/3.0/).
            |This derived data is shared under the same license, with attribution to the OSRS Wiki and its editors.
            |""".trimMargin()
    }
}
