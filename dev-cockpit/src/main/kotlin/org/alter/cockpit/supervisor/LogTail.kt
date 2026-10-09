package org.alter.cockpit.supervisor

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.alter.cockpit.events.EventBus
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/**
 * Follows `data/logs/alter.log` like `tail -f`: new lines go on the bus as `log` events and the last
 * [keep] lines stay in memory for the UI. Handles rotation (the file getting shorter) by starting over.
 */
class LogTail(private val file: Path, private val keep: Int, private val bus: EventBus, private val pollMs: Long = 1_000) {
    private val lines = ArrayDeque<String>()
    private val remainder = StringBuilder()
    private var position = 0L

    fun snapshot(limit: Int = keep): List<String> = synchronized(lines) { lines.takeLast(limit) }

    suspend fun run() {
        // Start from the current end: the UI wants what happens from now on, the file has the history.
        if (Files.exists(file)) position = Files.size(file)
        while (coroutineContext.isActive) {
            poll()
            delay(pollMs)
        }
    }

    /** Reads whatever was appended since the last call. Returns the new lines. */
    fun poll(): List<String> {
        if (!Files.exists(file)) return emptyList()
        val size = Files.size(file)
        if (size < position) {
            position = 0
            remainder.setLength(0)
        }
        if (size == position) return emptyList()
        val chunk = RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(position)
            val bytes = ByteArray((size - position).toInt())
            raf.readFully(bytes)
            position = size
            String(bytes, Charsets.UTF_8)
        }
        remainder.append(chunk)
        val complete = mutableListOf<String>()
        while (true) {
            val newline = remainder.indexOf("\n")
            if (newline < 0) break
            complete += remainder.substring(0, newline).trimEnd('\r')
            remainder.delete(0, newline + 1)
        }
        if (complete.isNotEmpty()) {
            synchronized(lines) {
                lines.addAll(complete)
                while (lines.size > keep) lines.removeFirst()
            }
            complete.forEach { bus.publish("log", it) }
        }
        return complete
    }
}
