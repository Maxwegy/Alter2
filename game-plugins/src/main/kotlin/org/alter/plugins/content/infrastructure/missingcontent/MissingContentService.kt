package org.alter.plugins.content.infrastructure.missingcontent

import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.config.InfraConfig
import org.alter.data.io.IoScope
import org.alter.data.missing.MissingContentEntry
import org.alter.data.missing.MissingContentEvent
import org.alter.data.missing.MissingContentStore
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps `data/missing_content.json` up to date: recording is in-memory and cheap (game thread), writing
 * happens periodically and on shutdown on the IO scope.
 */
class MissingContentService(
    private val store: MissingContentStore,
    private val io: IoScope,
    val config: InfraConfig.MissingContent,
    /** Told about every key seen for the first time (e.g. to stream it to the Dev Cockpit). */
    private val onFirstSeen: (MissingContentEvent) -> Unit = {},
) : Service {
    private val logger = KotlinLogging.logger {}

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        store.load()
        io.every(config.flushIntervalSeconds.seconds, "missing-content-flush") { store.flushIfDirty() }
        io.onClose("missing-content-flush") { store.flushIfDirty() }
        logger.info { "Missing-content log has ${store.size()} known entries." }
    }

    /** Returns true when [event]'s key is seen for the first time. */
    fun record(event: MissingContentEvent): Boolean {
        val first = store.record(event)
        if (first) {
            onFirstSeen(event)
            logger.info { "Missing content: ${event.key} ${event.name ?: ""} ${event.optionName ?: ""}".trimEnd() }
        }
        return first
    }

    fun top(n: Int): List<MissingContentEntry> = store.top(n)
}
