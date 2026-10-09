package org.alter.data.missing

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One occurrence of content the game doesn't handle yet (an unscripted interaction or a data gap). */
data class MissingContentEvent(
    /** Interaction type name (e.g. `NPC_OP`) or data-gap type (`NPC_NO_STATS`, `NPC_NO_DROPS`). */
    val type: String,
    val id: Int,
    val rawId: Int = id,
    val op: Int = -1,
    val usedId: Int = -1,
    val component: Int = -1,
    /** Cache name of the entity, for humans; not part of the key. */
    val name: String? = null,
    /** Option text, when known (e.g. "Talk-to"); not part of the key. */
    val optionName: String? = null,
    val location: Location? = null,
) {
    /** Entries are aggregated by this key: same thing, same action. */
    val key: String get() = "$type:$id:$usedId:$op:$component"
}

data class Location(val x: Int, val z: Int, val height: Int)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MissingContentEntry(
    val key: String,
    val type: String,
    val id: Int,
    val rawId: Int,
    val op: Int,
    val usedId: Int,
    val component: Int,
    val name: String?,
    val optionName: String?,
    val count: Long,
    val firstSeen: String,
    val lastSeen: String,
    val locations: List<Location>,
)

/** The on-disk contract of `data/missing_content.json`, read by the Dev Cockpit. */
data class MissingContentFile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val updatedAt: String,
    val entries: List<MissingContentEntry>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Aggregates missing-content events in memory and persists them atomically.
 *
 * [record] is cheap and thread-safe (called from the game thread); [flushIfDirty] does the IO and belongs
 * on the IO scope. Counts from an existing file are merged on [load], so the log accumulates across restarts.
 */
class MissingContentStore(
    private val file: Path,
    private val maxLocationsPerEntry: Int = 5,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<String, MissingContentEntry>()
    private val dirty = AtomicBoolean(false)

    fun load() {
        if (!Files.exists(file)) return
        try {
            val stored = mapper.readValue<MissingContentFile>(file.toFile())
            if (stored.schemaVersion != MissingContentFile.SCHEMA_VERSION) {
                logger.warn { "Ignoring $file: schemaVersion ${stored.schemaVersion} != ${MissingContentFile.SCHEMA_VERSION}." }
                return
            }
            stored.entries.forEach { entries[it.key] = it }
        } catch (e: Exception) {
            logger.error(e) { "Could not read $file; starting a fresh missing-content log." }
        }
    }

    /** Records [event]. Returns true when this key was seen for the first time. */
    fun record(event: MissingContentEvent): Boolean {
        val now = Instant.now(clock).toString()
        var firstTime = false
        entries.compute(event.key) { key, existing ->
            if (existing == null) {
                firstTime = true
                MissingContentEntry(
                    key = key,
                    type = event.type,
                    id = event.id,
                    rawId = event.rawId,
                    op = event.op,
                    usedId = event.usedId,
                    component = event.component,
                    name = event.name,
                    optionName = event.optionName,
                    count = 1,
                    firstSeen = now,
                    lastSeen = now,
                    locations = listOfNotNull(event.location),
                )
            } else {
                existing.copy(
                    count = existing.count + 1,
                    lastSeen = now,
                    name = existing.name ?: event.name,
                    optionName = existing.optionName ?: event.optionName,
                    locations = addLocation(existing.locations, event.location),
                )
            }
        }
        dirty.set(true)
        return firstTime
    }

    private fun addLocation(locations: List<Location>, location: Location?): List<Location> =
        if (location == null || location in locations || locations.size >= maxLocationsPerEntry) locations else locations + location

    /** All entries, most frequent first. */
    fun snapshot(): List<MissingContentEntry> =
        entries.values.sortedWith(compareByDescending<MissingContentEntry> { it.count }.thenBy { it.key })

    fun top(n: Int): List<MissingContentEntry> = snapshot().take(n)

    fun size(): Int = entries.size

    /** Writes the file if anything was recorded since the last flush. Returns true if it wrote. */
    fun flushIfDirty(): Boolean {
        if (!dirty.getAndSet(false)) return false
        val content = MissingContentFile(updatedAt = Instant.now(clock).toString(), entries = snapshot())
        try {
            AtomicFiles.write(file, mapper.writeValueAsBytes(content))
        } catch (e: Exception) {
            dirty.set(true)
            throw e
        }
        return true
    }

    private companion object {
        val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
