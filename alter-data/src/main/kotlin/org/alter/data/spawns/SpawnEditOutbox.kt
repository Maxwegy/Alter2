package org.alter.data.spawns

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.alter.data.io.IoScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends [SpawnEdit]s to the runtime outbox (`data/run/spawn-edits.jsonl`), one line each, in the order they
 * were recorded. [record] only queues the line, so the game thread never touches the disk; one coroutine on
 * the [io] scope writes the queue, and whatever is still queued is written when the scope closes. The outbox
 * is never read here: `spawnSync --apply-edits` applies it to `data/cfg/spawns/npcs`.
 */
class SpawnEditOutbox(private val path: Path, io: IoScope) {
    private val logger = KotlinLogging.logger {}
    private val queue = Channel<SpawnEdit>(Channel.UNLIMITED)

    private val writer = io.scope.launch(CoroutineName("spawn-edit-outbox")) {
        for (edit in queue) write(edit)
    }

    init {
        io.onClose("spawn-edit outbox") {
            queue.close()
            // The writer drains the queue in order and then ends; give it a bounded time at shutdown.
            runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { writer.join() } }
                ?: logger.warn { "Spawn edit outbox did not finish writing within ${CLOSE_TIMEOUT_MS}ms." }
        }
    }

    /** Queues [edit]; safe to call from the game thread. */
    fun record(edit: SpawnEdit) {
        if (queue.trySend(edit).isFailure) logger.warn { "Spawn edit outbox is closed; not recorded: ${SpawnEdits.line(edit)}" }
    }

    private fun write(edit: SpawnEdit) {
        val line = SpawnEdits.line(edit) + "\n"
        try {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.error(e) { "Could not append to $path; spawn edit lost: ${line.trim()}" }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
