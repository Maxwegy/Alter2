package org.alter.data.spawns

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpawnEditApplierTests {
    private val root: Path = Files.createTempDirectory("spawn-edits")
    private val spawnDir: Path = root.resolve("cfg/spawns/npcs")
    private val outbox: Path = root.resolve("run/spawn-edits.jsonl")
    private val now = Instant.parse("2026-10-10T12:34:56Z")
    private val at = "2026-10-10T12:00:00Z"

    private val hansWiki = Entries.hansWiki
    private val duke = Entries.manual("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH", note = "migrated")
    private val man = Entries.manual("npc.man", 3263, 3232, 0, 5)

    private fun edit(entry: NpcSpawnEntry, to: SpawnPlacement?) = SpawnEdit.of(at, entry, to)

    private fun edited(e: NpcSpawnEntry) = e.copy(source = SpawnEditApplier.editedSource(e.source, at))

    /** The same edit as a version-1 outbox line would give it: no id, matched by (npc, from tile). */
    private fun SpawnEdit.v1() = copy(id = null)

    private fun numbered(vararg edits: SpawnEdit) = edits.mapIndexed { i, e -> IndexedValue(i + 1, e) }

    private fun seed(vararg entries: NpcSpawnEntry, edits: List<SpawnEdit>) {
        NpcSpawnFiles.write(spawnDir, NpcSpawnFiles.group(entries.toList()))
        Files.createDirectories(outbox.parent)
        Files.writeString(outbox, edits.joinToString("") { SpawnEdits.line(it) + "\n" })
    }

    private fun entries(): List<NpcSpawnEntry> = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(spawnDir)).entries

    @Test
    fun `a move keeps the id and makes the entry an edit`() {
        val result = SpawnEditApplier.apply(listOf(duke, hansWiki), numbered(edit(duke, SpawnPlacement(3215, 3220, 1, 4, "SOUTH"))))
        assertEquals(1, result.applied)
        assertEquals(emptyList(), result.unmatched)
        assertEquals(listOf(hansWiki, duke.copy(x = 3215, source = NpcSpawnSource.Edit(at))), result.entries)
    }

    @Test
    fun `an edited wiki entry becomes an edit entry that keeps its page and map`() {
        val result = SpawnEditApplier.apply(listOf(hansWiki), numbered(edit(hansWiki, SpawnPlacement(3212, 3219, 0, 3, "EAST"))))
        val e = result.entries.single()
        assertEquals(NpcSpawnSource.Edit(at, Entries.HANS_PAGE, Entries.HANS_MAP), e.source)
        assertEquals("w06bd7d9b22e4", e.id)
        assertEquals(3, e.walkRadius)
        assertEquals("EAST", e.direction)
        // A second edit keeps the page and map and takes the newer instant.
        val again = SpawnEditApplier.apply(result.entries, numbered(SpawnEdit.of("2026-10-11T00:00:00Z", e, SpawnPlacement.of(e).copy(walkRadius = 1))))
        assertEquals(NpcSpawnSource.Edit("2026-10-11T00:00:00Z", Entries.HANS_PAGE, Entries.HANS_MAP), again.entries.single().source)
    }

    @Test
    fun `a delete removes the entry`() {
        val result = SpawnEditApplier.apply(listOf(duke, hansWiki), numbered(edit(duke, null)))
        assertEquals(listOf(hansWiki), result.entries)
        assertEquals(1, result.deleted)
    }

    @Test
    fun `an add inserts an edit entry with the line's id`() {
        val add = SpawnEdit.add(at, "npc.man", SpawnPlacement(3222, 3218, 0, 2))
        val result = SpawnEditApplier.apply(listOf(duke), numbered(add))
        assertEquals(1, result.added)
        assertEquals(1, result.applied)
        assertEquals(
            listOf(duke, NpcSpawnEntry("me4c8acb121d1", "npc.man", 3222, 3218, 0, 2, null, NpcSpawnSource.Edit(at))),
            result.entries,
        )
    }

    @Test
    fun `an add onto an occupied tile or with an existing id is skipped`() {
        val onDuke = SpawnEdit.add(at, "npc.duke_of_lumbridge", SpawnPlacement(3212, 3220, 1, 0))
        val sameId = SpawnEdit.add(at, "npc.man", SpawnPlacement(3222, 3218, 0, 2)).copy(id = duke.id)
        val result = SpawnEditApplier.apply(listOf(duke), numbered(onDuke, sameId))
        assertEquals(listOf(duke), result.entries)
        assertEquals(0, result.added)
        assertEquals(listOf("npc.duke_of_lumbridge already spawns at (3212, 3220, 1)", "id ${duke.id} already exists"), result.unmatched.map { it.reason })
        assertEquals("line 0001: npc.duke_of_lumbridge add at (3212, 3220, 1): npc.duke_of_lumbridge already spawns at (3212, 3220, 1)", result.unmatched[0].toString())
    }

    @Test
    fun `edits find their entry by id, even after it moved`() {
        val moved = duke.copy(x = 3215)
        // The second line names the original tile in from, as a stale outbox might; the id still finds the entry.
        val result = SpawnEditApplier.apply(
            listOf(duke),
            numbered(edit(duke, SpawnPlacement.of(moved)), edit(duke, SpawnPlacement.of(moved).copy(walkRadius = 0))),
        )
        assertEquals(listOf(edited(moved.copy(walkRadius = 0))), result.entries)
        assertEquals(2, result.applied)
    }

    @Test
    fun `version-1 lines match by npc and from tile, in order`() {
        val moved = duke.copy(x = 3215)
        val result = SpawnEditApplier.apply(
            listOf(duke),
            numbered(edit(duke, SpawnPlacement.of(moved)).v1(), edit(moved, SpawnPlacement.of(moved).copy(walkRadius = 0)).v1()),
        )
        assertEquals(listOf(edited(moved.copy(walkRadius = 0))), result.entries)
        assertEquals(2, result.applied)
    }

    @Test
    fun `an edit with no matching entry, or onto an occupied tile, is reported and skipped`() {
        val ghost = Entries.manual("npc.duke_of_lumbridge", 3000, 3220, 1, 4)
        val onHans = SpawnPlacement(3212, 3219, 0, 0)
        val hansTwin = Entries.manual("npc.hans", 3213, 3219, 0, 11)
        val result = SpawnEditApplier.apply(
            listOf(duke, hansWiki, hansTwin),
            numbered(edit(ghost, null), edit(hansTwin, onHans), edit(ghost, null).v1(), edit(hansTwin, null).copy(npc = "npc.man")),
        )
        assertEquals(listOf(duke, hansWiki, hansTwin).sortedWith(NpcSpawnFiles.canonicalOrder), result.entries)
        assertEquals(0, result.applied)
        assertEquals(listOf(1, 2, 3, 4), result.unmatched.map { it.line })
        assertEquals("no entry with id ${ghost.id}", result.unmatched[0].reason)
        assertTrue("already spawns" in result.unmatched[1].reason)
        assertEquals("no entry for this npc on that tile", result.unmatched[2].reason)
        assertEquals("id ${hansTwin.id} is npc.hans, not npc.man", result.unmatched[3].reason)
    }

    @Test
    fun `crossing x 3263 to 3264 moves the entry from 12850 to 13106`() {
        seed(man, duke, edits = listOf(edit(man, SpawnPlacement(3264, 3232, 0, 5))))
        val result = assertIs<SpawnEditApplier.Result.Written>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertEquals(1, result.applied.moved)
        assertEquals(listOf("12850.json", "13106.json"), result.write.written)
        assertTrue("npc.man" !in Files.readString(spawnDir.resolve("12850.json")))
        assertTrue("\"x\": 3264" in Files.readString(spawnDir.resolve("13106.json")))
        assertEquals(listOf(duke, edited(man.copy(x = 3264))), entries())
    }

    @Test
    fun `add, move and remove from one outbox are written and reported`() {
        val add = SpawnEdit.add(at, "npc.man", SpawnPlacement(3222, 3218, 0, 2))
        seed(man, duke, hansWiki, edits = listOf(add, edit(hansWiki, SpawnPlacement(3213, 3219, 0, 3)), edit(duke, null)))
        val result = assertIs<SpawnEditApplier.Result.Written>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertEquals(mapOf("applied" to 3, "added" to 1, "moved" to 0, "deleted" to 1, "unmatched" to 0), result.report.summary.filterKeys { it in setOf("applied", "added", "moved", "deleted", "unmatched") })
        val byId = entries().associateBy { it.id }
        assertEquals(setOf("me4c8acb121d1", hansWiki.id, man.id), byId.keys)
        assertEquals(NpcSpawnSource.Edit(at), byId.getValue("me4c8acb121d1").source)
        assertEquals(3213, byId.getValue(hansWiki.id).x)
    }

    @Test
    fun `moving the last entry out of a region deletes its file`() {
        seed(man, edits = listOf(edit(man, SpawnPlacement(3264, 3232, 0, 5))))
        val result = assertIs<SpawnEditApplier.Result.Written>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertEquals(listOf("12850.json"), result.write.removed)
        assertFalse(Files.exists(spawnDir.resolve("12850.json")))
    }

    @Test
    fun `the applied outbox is renamed and unmatched edits are in the report`() {
        seed(duke, edits = listOf(edit(duke, null), edit(hansWiki, null).v1()))
        val result = assertIs<SpawnEditApplier.Result.Written>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertFalse(Files.exists(outbox))
        assertEquals("spawn-edits.applied-20261010-123456.jsonl", result.archived.fileName.toString())
        assertEquals(2, Files.readAllLines(result.archived).size)
        assertEquals(1, result.report.summary["unmatched"])
        val section = result.report.sections.single { it.title == "Unmatched edits (not applied)" }
        assertEquals(listOf("line 0002: npc.hans from (3212, 3219, 0): no entry for this npc on that tile"), section.items)
        assertEquals(emptyList(), entries())
        assertFalse(Files.exists(spawnDir.resolve("12850.json")))
    }

    @Test
    fun `no outbox means nothing to apply`() {
        NpcSpawnFiles.write(spawnDir, NpcSpawnFiles.group(listOf(duke)))
        assertIs<SpawnEditApplier.Result.NothingToApply>(SpawnEditApplier.run(spawnDir, outbox, now))
    }

    @Test
    fun `an unreadable line rejects the run and keeps the outbox`() {
        seed(duke, edits = listOf(edit(duke, null)))
        Files.writeString(outbox, Files.readString(outbox) + "{ nope\n")
        val before = Files.readString(outbox)
        assertIs<SpawnEditApplier.Result.Rejected>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertEquals(before, Files.readString(outbox))
        assertEquals(listOf(duke), entries())
    }
}
