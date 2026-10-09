package org.alter.game.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class GameServiceJobQueueTests {
    @Test
    fun `job submitted by a running job runs on the next drain`() {
        val service = GameService()
        val ran = mutableListOf<String>()
        service.submitGameThreadJob {
            ran += "A"
            service.submitGameThreadJob { ran += "B" }
        }
        service.drainGameThreadJobs()
        assertEquals(listOf("A"), ran)
        service.drainGameThreadJobs()
        assertEquals(listOf("A", "B"), ran)
    }

    @Test
    fun `no job is lost under concurrent producers`() {
        val service = GameService()
        val executed = AtomicInteger()
        val producers = 4
        val perProducer = 10_000
        val start = CountDownLatch(1)
        val threads = (0 until producers).map {
            thread {
                start.await()
                repeat(perProducer) { service.submitGameThreadJob { executed.incrementAndGet() } }
            }
        }
        start.countDown()
        while (threads.any { it.isAlive }) {
            service.drainGameThreadJobs()
        }
        service.drainGameThreadJobs()
        assertEquals(producers * perProducer, executed.get())
    }
}
