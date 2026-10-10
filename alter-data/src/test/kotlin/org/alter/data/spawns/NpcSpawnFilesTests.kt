package org.alter.data.spawns

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NpcSpawnFilesTests {
    private val dir: Path = Files.createTempDirectory("npc-spawns")

    private val hansWiki = NpcSpawnEntry(
        npc = "npc.hans", x = 3212, z = 3219, height = 0, walkRadius = 11,
        source = NpcSpawnSource.Wiki("https://oldschool.runescape.wiki/w/Hans", "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}"),
    )
    private val hansManual = NpcSpawnEntry("npc.hans", 3221, 3219, 0, 0, "EAST", NpcSpawnSource.Manual, note = "migrated from content/areas/lumbridge/npcs/HansPlugin.kt")
    private val duke = NpcSpawnEntry("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH", NpcSpawnSource.Manual)
    private val shopKeeper = NpcSpawnEntry("npc.generalshopkeeper1", 3211, 3246, 0, 3, "EAST", NpcSpawnSource.Manual, origin = "https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)")

    private fun read(): NpcSpawnFiles.ReadResult.Read = assertIs(NpcSpawnFiles.readAll(dir))

    private fun writeRaw(name: String, text: String) = Files.writeString(dir.resolve(name), text)

    @Test
    fun `written files read back unchanged`() {
        val files = NpcSpawnFiles.group(listOf(hansManual, hansWiki, duke, shopKeeper))
        val result = NpcSpawnFiles.write(dir, files)
        assertEquals(listOf("12850.json"), result.written)
        val read = read()
        assertEquals(emptyList(), read.errors)
        assertEquals(listOf(hansManual, hansWiki, duke, shopKeeper).sortedWith(NpcSpawnFiles.canonicalOrder), read.entries)
    }

    @Test
    fun `output is canonical, LF only, with a trailing newline, and a rewrite is a no-op`() {
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(shopKeeper, duke, hansManual, hansWiki)))
        val text = Files.readString(dir.resolve("12850.json"))
        assertFalse('\r' in text)
        assertTrue(text.endsWith("}\n"))
        val order = listOf("npc.generalshopkeeper1", "npc.hans\",\n      \"x\": 3212", "npc.duke_of_lumbridge", "npc.hans\",\n      \"x\": 3221")
        assertEquals(order.sortedBy { text.indexOf(it) }, order, "entries sorted by x, z, height, npc")
        assertTrue(text.startsWith("{\n  \"schemaVersion\": 1,\n  \"regionId\": 12850,\n  \"spawns\": [\n    {\n      \"npc\": \"npc.generalshopkeeper1\",\n"))
        val again = NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(hansWiki, hansManual, duke, shopKeeper)))
        assertEquals(emptyList(), again.written)
    }

    @Test
    fun `manual sorts before wiki on the same tile`() {
        val wikiTwin = hansWiki.copy(x = 3221, walkRadius = 0)
        val text = NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(wikiTwin, hansManual)))
        assertTrue(text.indexOf("\"source\": \"manual\"") < text.indexOf("\"page\""))
    }

    @Test
    fun `empty and stale region files are deleted, the README is kept`() {
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(hansManual, NpcSpawnEntry("npc.man", 3264, 3232, 0, 8, null, NpcSpawnSource.Manual))))
        writeRaw("README.md", "spawns\n")
        val result = NpcSpawnFiles.write(dir, listOf(NpcSpawnFile(12850, emptyList())))
        assertEquals(listOf("12850.json", "13106.json"), result.removed)
        assertTrue(Files.exists(dir.resolve("README.md")))
        assertEquals(emptyList(), read().files)
    }

    @Test
    fun `a missing directory is reported as missing, not as an error`() {
        assertEquals(NpcSpawnFiles.ReadResult.Missing, NpcSpawnFiles.readAll(dir.resolve("absent")))
    }

    @Test
    fun `a file name that is not its regionId is an error`() {
        writeRaw("12851.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual))))
        writeRaw("lumbridge.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(duke))))
        val errors = read().errors
        assertTrue(errors.any { it.startsWith("12851.json:") && "does not match the file name" in it }, errors.toString())
        assertTrue(errors.any { it.startsWith("lumbridge.json:") && "<regionId>.json" in it }, errors.toString())
    }

    @Test
    fun `an entry outside its file's region is an error`() {
        val outside = NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual))).replace("\"x\": 3221", "\"x\": 3264")
        writeRaw("12850.json", outside)
        val read = read()
        assertEquals(listOf("12850.json: spawns[0]: npc.hans at (3264, 3219) is in region 13106, not 12850"), read.errors)
        assertEquals(emptyList(), read.files)
    }

    @Test
    fun `bad sources, shapes and JSON are all reported`() {
        val good = NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansWiki)))
        writeRaw("12850.json", good.replace("https://oldschool.runescape.wiki/w/Hans", "https://example.com/Hans"))
        writeRaw("12851.json", """{ "schemaVersion": 2, "regionId": 12851, "spawns": [ { "npc": "hans", "x": 3212, "z": 3264, "height": 4, "walkRadius": -1, "source": "wiki", "extra": 1 } ] }""")
        writeRaw("12852.json", "{ not json")
        val errors = read().errors
        assertTrue(errors.any { it.startsWith("12850.json: spawns[0]: source.page must be a URL starting with https://oldschool.runescape.wiki/w/") }, errors.toString())
        listOf("schemaVersion must be 1", "unknown field 'extra'", "npc must be an RSCM name", "height must be an integer in 0..3", "walkRadius must be an integer", "source must be \"manual\"")
            .forEach { expected -> assertTrue(errors.any { it.startsWith("12851.json:") && expected in it }, "missing '$expected' in $errors") }
        assertTrue(errors.any { it.startsWith("12852.json: not valid JSON") }, errors.toString())
    }

    @Test
    fun `the same npc on the same tile twice is an error`() {
        writeRaw("12850.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual, hansManual.copy(walkRadius = 3)))))
        assertEquals(listOf("duplicate spawn npc.hans at (3221, 3219, 0) in 12850.json"), read().errors)
    }

    @Test
    fun `CRLF files read the same as LF files`() {
        writeRaw("12850.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(duke))).replace("\n", "\r\n"))
        assertEquals(listOf(duke), read().entries)
        assertEquals(emptyList(), NpcSpawnFiles.write(dir, listOf(NpcSpawnFile(12850, listOf(duke)))).written)
    }
}
