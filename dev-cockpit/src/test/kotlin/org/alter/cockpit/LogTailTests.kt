package org.alter.cockpit

import org.alter.cockpit.events.EventBus
import org.alter.cockpit.supervisor.LogTail
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class LogTailTests {
    private val file = Files.createTempDirectory("logs").resolve("alter.log")
    private val bus = EventBus()
    private val tail = LogTail(file, keep = 3, bus = bus)

    private fun append(text: String) = Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

    @Test
    fun `complete lines are published and only the last few are kept`() {
        assertEquals(emptyList<String>(), tail.poll())
        append("one\r\ntwo\nthr")
        assertEquals(listOf("one", "two"), tail.poll())
        append("ee\nfour\n")
        assertEquals(listOf("three", "four"), tail.poll())
        assertEquals(listOf("two", "three", "four"), tail.snapshot())
        assertEquals(listOf("one", "two", "three", "four"), bus.flow.replayCache.map { it.payload })
    }

    @Test
    fun `a rotated file is read from the start`() {
        append("a\nb\nc\n")
        tail.poll()
        Files.writeString(file, "x\n")
        assertEquals(listOf("x"), tail.poll())
    }
}
