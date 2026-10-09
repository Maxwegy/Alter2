package org.alter.game.saving

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically saves every online player so a crash or kill loses at most one interval of progress.
 *
 * State is captured on the game thread ([snapshot] builds detached documents); the disk writes happen
 * on a background thread so saving never stalls the 600ms tick.
 */
class PlayerAutosave<P, D>(
    private val intervalMillis: Long,
    private val runOnGameThread: (() -> Unit) -> Unit,
    private val snapshot: () -> List<Pair<P, D>>,
    private val write: (P, D) -> Unit,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "player-autosave").apply { isDaemon = true }
    }

    fun start() {
        executor.scheduleWithFixedDelay(::saveOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        logger.info { "Player autosave every ${TimeUnit.MILLISECONDS.toMinutes(intervalMillis)} minutes." }
    }

    /** Captures on the game thread, then writes on the autosave thread. */
    fun saveOnce() {
        runOnGameThread {
            val documents = snapshot()
            executor.execute { writeAll(documents) }
        }
    }

    internal fun writeAll(documents: List<Pair<P, D>>) {
        documents.forEach { (player, document) ->
            try {
                write(player, document)
            } catch (e: Exception) {
                logger.error(e) { "Autosave failed for $player." }
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
