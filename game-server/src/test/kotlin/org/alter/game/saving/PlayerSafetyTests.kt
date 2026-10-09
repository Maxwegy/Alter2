package org.alter.game.saving

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSafetyTests {
    @Test
    fun `shutdown saves each player exactly once on the game thread`() {
        val gameThread = Executors.newSingleThreadExecutor { Thread(it, "game-context") }
        val saved = mutableListOf<Pair<String, String>>()
        val saver = ShutdownSaver(
            players = { listOf("alice", "bob") },
            save = { saved += it to Thread.currentThread().name },
            runOnGameThread = { job -> gameThread.execute(job) },
        )
        assertEquals(2, saver.saveAll())
        assertEquals(listOf("alice" to "game-context", "bob" to "game-context"), saved)
        gameThread.shutdown()
    }

    @Test
    fun `shutdown falls back to the calling thread when the game thread is stuck`() {
        val saved = mutableListOf<String>()
        val saver = ShutdownSaver(
            players = { listOf("alice") },
            save = { saved += it },
            runOnGameThread = { /* game thread never runs the job */ },
            timeoutMillis = 50,
        )
        assertEquals(1, saver.saveAll())
        assertEquals(listOf("alice"), saved)
    }

    @Test
    fun `one failing save does not stop the others`() {
        val saved = mutableListOf<String>()
        val saver = ShutdownSaver(
            players = { listOf("alice", "broken", "bob") },
            save = { if (it == "broken") error("disk full") else saved += it },
            runOnGameThread = { it() },
        )
        assertEquals(2, saver.saveAll())
        assertEquals(listOf("alice", "bob"), saved)
    }

    @Test
    fun `autosave snapshots on the game thread and writes elsewhere`() {
        val gameThread = Executors.newSingleThreadExecutor { Thread(it, "game-context") }
        val snapshotThreads = mutableListOf<String>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val autosave = PlayerAutosave(
            intervalMillis = 60_000,
            runOnGameThread = { job -> gameThread.execute(job) },
            snapshot = {
                snapshotThreads += Thread.currentThread().name
                listOf("alice" to "doc-a", "bob" to "doc-b")
            },
            write = { player, doc -> writes += "$player:$doc" to Thread.currentThread().name },
        )
        autosave.saveOnce()
        gameThread.shutdown()
        gameThread.awaitTermination(5, TimeUnit.SECONDS)
        autosave.close()
        Thread.sleep(200)
        assertEquals(listOf("game-context"), snapshotThreads)
        assertEquals(listOf("alice:doc-a", "bob:doc-b"), writes.map { it.first })
        assertEquals(setOf("player-autosave"), writes.map { it.second }.toSet())
    }
}
