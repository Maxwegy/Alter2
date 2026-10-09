package org.alter.cockpit.inbox.sources

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.alter.cockpit.Json
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.InboxService
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.wiki.PageResolver
import org.alter.data.missing.MissingContentEntry
import org.alter.data.missing.MissingContentEvent
import org.alter.data.missing.MissingContentFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/**
 * Turns `data/missing_content.json` (and the server's live `missing` events) into inbox cards: one card per
 * key, kind `enrich.<type>`, with the hit count and places as evidence and the wiki lookup as the plan.
 */
class MissingContentSource(
    private val file: Path,
    private val inbox: InboxService,
    /** The exact requests GO would make for these params, shown on the card ([EnrichmentService.plan]). */
    private val planner: (Map<String, Any?>) -> List<PlanStep>,
    private val minCount: Long = 1,
    private val pollMs: Long = 30_000,
) {
    private val logger = KotlinLogging.logger {}
    private var lastModified = -1L

    suspend fun run() {
        while (coroutineContext.isActive) {
            runCatching { pollFile() }.onFailure { logger.warn(it) { "Could not read $file" } }
            delay(pollMs)
        }
    }

    /** Re-reads the file when it changed. Returns how many cards were created. */
    fun pollFile(): Int {
        if (!Files.exists(file)) return 0
        val modified = Files.getLastModifiedTime(file).toMillis()
        if (modified == lastModified) return 0
        lastModified = modified
        val stored = Json.mapper.readValue<MissingContentFile>(file.toFile())
        if (stored.schemaVersion != MissingContentFile.SCHEMA_VERSION) {
            logger.warn { "$file has schemaVersion ${stored.schemaVersion}; this cockpit reads ${MissingContentFile.SCHEMA_VERSION}" }
            return 0
        }
        return stored.entries.filter { it.count >= minCount }.count { propose(it) != null }
    }

    /** A `missing` event from the running server: the key was just seen for the first time. */
    fun onLiveEvent(event: MissingContentEvent) {
        val now = java.time.Instant.now().toString()
        val entry = MissingContentEntry(
            event.key, event.type, event.id, event.rawId, event.op, event.usedId, event.component, event.name, event.optionName,
            count = 1, firstSeen = now, lastSeen = now, locations = listOfNotNull(event.location),
        )
        if (entry.count >= minCount) propose(entry)
    }

    private fun propose(entry: MissingContentEntry): InboxAction? {
        val params = mapOf(
            "type" to entry.type, "id" to entry.id, "usedId" to entry.usedId.takeIf { it >= 0 }, "name" to entry.name,
            "optionName" to entry.optionName, "lookupType" to PageResolver.lookupType(entry.type),
        )
        return inbox.propose(
            kind = "enrich.${entry.type.lowercase()}",
            sourceKey = "missing:${entry.key}",
            title = title(entry),
            summary = "${entry.count}× since ${entry.firstSeen.take(10)}, last ${entry.lastSeen.take(16).replace('T', ' ')}" +
                (entry.locations.firstOrNull()?.let { " near ${it.x},${it.z},${it.height}" } ?: ""),
            evidence = mapOf(
                "key" to entry.key, "type" to entry.type, "id" to entry.id, "rawId" to entry.rawId, "op" to entry.op,
                "usedId" to entry.usedId, "component" to entry.component, "name" to entry.name, "optionName" to entry.optionName,
                "count" to entry.count, "firstSeen" to entry.firstSeen, "lastSeen" to entry.lastSeen,
                "locations" to entry.locations.map { mapOf("x" to it.x, "z" to it.z, "height" to it.height) },
            ),
            plan = planner(params),
            params = params,
        )
    }

    companion object {
        fun title(entry: MissingContentEntry): String {
            val what = entry.name?.let { "$it (${entry.id})" } ?: "${entry.type} ${entry.id}"
            return when (entry.type) {
                "NPC_NO_STATS" -> "$what has no combat stats"
                "NPC_NO_DROPS" -> "$what has no drop table"
                else -> "${entry.optionName ?: "Option ${entry.op}"} on $what is unscripted"
            }
        }
    }
}
