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

    private val hansPage = "https://oldschool.runescape.wiki/w/Hans"
    private val hansWiki = Entries.hansWiki
    private val duke = Entries.manual("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH", note = "migrated")
    private val man = Entries.manual("npc.man", 3263, 3232, 0, 5)

    private fun edit(entry: NpcSpawnEntry, to: SpawnPlacement?) = SpawnEdit.of("2026-10-10T12:00:00Z", entry, to)

    private fun edited(e: NpcSpawnEntry) = e.copy(source = SpawnEditApplier.editedSource(e.source, "2026-10-10T12:00:00Z"))

    private fun numbered(vararg edits: SpawnEdit) = edits.mapIndexed { i, e -> IndexedValue(i + 1, e) }

    private fun seed(vararg entries: NpcSpawnEntry, edits: List<SpawnEdit>) {
        NpcSpawnFiles.write(spawnDir, NpcSpawnFiles.group(entries.toList()))
        Files.createDirectories(outbox.parent)
        Files.writeString(outbox, edits.joinToString("") { SpawnEdits.line(it) + "\n" })
    }

    private fun entries(): List<NpcSpawnEntry> = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(spawnDir)).entries

    @Test
    fun `a move rewrites the entry`() {
        val result = SpawnEditApplier.apply(listOf(duke, hansWiki), numbered(edit(duke, SpawnPlacement(3215, 3220, 1, 4, "SOUTH"))))
        assertEquals(1, result.applied)
        assertEquals(emptyList(), result.unmatched)
        assertEquals(listOf(hansWiki, edited(duke.copy(x = 3215))), result.entries)
    }

    @Test
    fun `an edited wiki entry becomes an edit entry that keeps its page and map`() {
        val result = SpawnEditApplier.apply(listOf(hansWiki), numbered(edit(hansWiki, SpawnPlacement(3212, 3219, 0, 3, "EAST"))))
        val edited = result.entries.single()
        assertEquals(NpcSpawnSource.Edit("2026-10-10T12:00:00Z", hansPage, Entries.HANS_MAP), edited.source)
        assertEquals(hansWiki.id, edited.id)
        assertEquals(3, edited.walkRadius)
        assertEquals("EAST", edited.direction)
    }

    @Test
    fun `a delete removes the entry`() {
        val result = SpawnEditApplier.apply(listOf(duke, hansWiki), numbered(edit(duke, null)))
        assertEquals(listOf(hansWiki), result.entries)
        assertEquals(1, result.deleted)
    }

    @Test
    fun `later edits match the earlier edit's result`() {
        val moved = duke.copy(x = 3215)
        val result = SpawnEditApplier.apply(
            listOf(duke),
            numbered(edit(duke, SpawnPlacement.of(moved)), edit(moved, SpawnPlacement.of(moved).copy(walkRadius = 0))),
        )
        assertEquals(listOf(edited(moved.copy(walkRadius = 0))), result.entries)
        assertEquals(2, result.applied)
    }

    @Test
    fun `an edit with no matching entry, or onto an occupied tile, is reported and skipped`() {
        val ghost = duke.copy(x = 3000)
        val onHans = SpawnPlacement(3212, 3219, 0, 0)
        val hansTwin = Entries.manual("npc.hans", 3213, 3219, 0, 11)
        val result = SpawnEditApplier.apply(
            listOf(duke, hansWiki, hansTwin),
            numbered(edit(ghost, null), edit(hansTwin, onHans)),
        )
        assertEquals(listOf(duke, hansWiki, hansTwin).sortedWith(NpcSpawnFiles.canonicalOrder), result.entries)
        assertEquals(0, result.applied)
        assertEquals(listOf(1, 2), result.unmatched.map { it.line })
        assertEquals("no entry for this npc on that tile", result.unmatched[0].reason)
        assertTrue("already spawns" in result.unmatched[1].reason)
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
    fun `moving the last entry out of a region deletes its file`() {
        seed(man, edits = listOf(edit(man, SpawnPlacement(3264, 3232, 0, 5))))
        val result = assertIs<SpawnEditApplier.Result.Written>(SpawnEditApplier.run(spawnDir, outbox, now))
        assertEquals(listOf("12850.json"), result.write.removed)
        assertFalse(Files.exists(spawnDir.resolve("12850.json")))
    }

    @Test
    fun `the applied outbox is renamed and unmatched edits are in the report`() {
        seed(duke, edits = listOf(edit(duke, null), edit(hansWiki, null)))
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
