package org.alter.game.saving

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Saves every online player when the JVM shuts down (Ctrl+C, SIGTERM, `docker stop`, the IDE stop button).
 *
 * The save runs on the game thread so it sees consistent state. If the game thread doesn't respond within
 * [timeoutMillis] (stalled or already gone), it falls back to saving from the calling thread: a possibly
 * inconsistent save beats losing progress. Saves are atomic, so a late duplicate save is harmless.
 */
class ShutdownSaver<P>(
    private val players: () -> List<P>,
    private val save: (P) -> Unit,
    private val runOnGameThread: (() -> Unit) -> Unit,
    private val timeoutMillis: Long = 10_000,
) {
    private val logger = KotlinLogging.logger {}

    /** Returns how many players were saved. */
    fun saveAll(): Int {
        if (players().isEmpty()) {
            // e.g. ::shutdown already logged everyone out, and its game thread is blocked in exitProcess.
            return 0
        }
        val done = CountDownLatch(1)
        var saved = 0
        runOnGameThread {
            try {
                saved = saveEach()
            } finally {
                done.countDown()
            }
        }
        if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return saved
        }
        logger.warn { "Game thread did not respond within ${timeoutMillis}ms; saving players from the shutdown thread." }
        return saveEach()
    }

    private fun saveEach(): Int {
        var count = 0
        players().forEach { player ->
            try {
                save(player)
                count++
            } catch (e: Exception) {
                logger.error(e) { "Failed to save $player during shutdown." }
            }
        }
        return count
    }
}
