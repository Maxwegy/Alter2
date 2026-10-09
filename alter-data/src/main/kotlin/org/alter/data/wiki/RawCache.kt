package org.alter.data.wiki

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.io.AtomicFiles
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gitignored raw API responses (`data/wiki-cache/<name>.json.gz`). Re-normalizing (e.g. after a mapping
 * change) never needs the network, and repeated syncs within the TTL stay light on the wiki.
 */
class RawCache(private val dir: Path) {
    data class Entry(val name: String, val query: String, val fetchedAt: String, val rows: List<BucketRow>)

    private fun file(name: String) = dir.resolve("$name.json.gz")

    /** The cached rows if present and (when [maxAge] is given) younger than [maxAge]. */
    fun read(name: String, maxAge: Duration? = null, now: Instant = Instant.now()): Entry? {
        val file = file(name)
        if (!Files.exists(file)) return null
        val entry = GZIPInputStream(Files.newInputStream(file)).use { mapper.readValue<Entry>(it) }
        if (maxAge != null && Instant.parse(entry.fetchedAt).plus(maxAge).isBefore(now)) return null
        return entry
    }

    fun write(entry: Entry) {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { mapper.writeValue(it, entry) } }.toByteArray()
        AtomicFiles.write(file(entry.name), bytes)
    }

    private companion object {
        val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    }
}
