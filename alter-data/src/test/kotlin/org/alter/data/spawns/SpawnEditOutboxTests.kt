package org.alter.data.spawns

import org.alter.data.io.IoScope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SpawnEditOutboxTests {
    @Test
    fun `recorded edits are appended in order, one line each, and flushed on close`() {
        val path = Files.createTempDirectory("outbox").resolve("run/spawn-edits.jsonl")
        val io = IoScope("outbox-test")
        val outbox = SpawnEditOutbox(path, io)
        val duke = Entries.manual("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH")
        val edits = (0 until 50).map { SpawnEdit.of("2026-10-10T12:00:00Z", duke, SpawnPlacement.of(duke).copy(walkRadius = it)) }
        edits.forEach(outbox::record)
        io.close()
        assertEquals(edits.map(SpawnEdits::line), Files.readAllLines(path))
        assertEquals(edits.map { IndexedValue(it.to!!.walkRadius + 1, it) }, SpawnEdits.parse(Files.readString(path)).edits)
    }
}
