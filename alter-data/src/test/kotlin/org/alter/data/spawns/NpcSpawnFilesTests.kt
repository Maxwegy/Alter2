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

    private val hansWiki = Entries.hansWiki
    private val hansManual = Entries.manual("npc.hans", 3221, 3219, 0, 0, "EAST", note = "migrated from content/areas/lumbridge/npcs/HansPlugin.kt")
    private val duke = Entries.manual("npc.duke_of_lumbridge", 3212, 3220, 1, 4, "SOUTH")
    private val shopKeeper = NpcSpawnEntry(
        "w8e44ba740e77", "npc.generalshopkeeper1", 3211, 3246, 0, 3, "EAST",
        NpcSpawnSource.Edit("2026-10-10T12:00:00Z", "https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)", "{{Map|name=Shop keeper|3211,3247|r=3}}"),
    )
    private val added = NpcSpawnEntry("me4c8acb121d1", "npc.man", 3222, 3218, 0, 2, null, NpcSpawnSource.Edit("2026-10-10T12:00:00Z"))

    private fun read(): NpcSpawnFiles.ReadResult.Read = assertIs(NpcSpawnFiles.readAll(dir))

    private fun writeRaw(name: String, text: String) = Files.writeString(dir.resolve(name), text)

    @Test
    fun `written files read back unchanged`() {
        val all = listOf(hansManual, hansWiki, duke, shopKeeper, added)
        val result = NpcSpawnFiles.write(dir, NpcSpawnFiles.group(all))
        assertEquals(listOf("12850.json"), result.written)
        val read = read()
        assertEquals(emptyList(), read.errors)
        assertEquals(all.sortedWith(NpcSpawnFiles.canonicalOrder), read.entries)
    }

    @Test
    fun `optional wiki provenance round-trips`() {
        val offSurface = hansWiki.copy(source = NpcSpawnSource.Wiki(Entries.HANS_PAGE, Entries.HANS_MAP, rule = "nonSurface", mapId = 7))
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(offSurface)))
        val text = Files.readString(dir.resolve("12850.json"))
        assertTrue("\"mapId\": 7,\n        \"rule\": \"nonSurface\"\n" in text, text)
        assertEquals(listOf(offSurface), read().entries)
    }

    @Test
    fun `output is canonical, LF only, with a trailing newline, and a rewrite is a no-op`() {
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(shopKeeper, duke, hansManual, hansWiki)))
        val text = Files.readString(dir.resolve("12850.json"))
        assertFalse('\r' in text)
        assertTrue(text.endsWith("}\n"))
        val order = listOf("npc.generalshopkeeper1", "npc.hans\",\n      \"x\": 3212", "npc.duke_of_lumbridge", "npc.hans\",\n      \"x\": 3221")
        assertEquals(order.sortedBy { text.indexOf(it) }, order, "entries sorted by x, z, height, npc")
        assertTrue(
            text.startsWith(
                "{\n  \"schemaVersion\": 2,\n  \"regionId\": 12850,\n  \"spawns\": [\n    {\n      \"id\": \"w8e44ba740e77\",\n" +
                    "      \"npc\": \"npc.generalshopkeeper1\",\n      \"x\": 3211,\n      \"z\": 3246,\n      \"height\": 0,\n      \"walkRadius\": 3,\n" +
                    "      \"direction\": \"EAST\",\n      \"source\": {\n        \"kind\": \"edit\",\n        \"at\": \"2026-10-10T12:00:00Z\",\n" +
                    "        \"page\": \"https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)\",\n        \"map\": \"{{Map|name=Shop keeper|3211,3247|r=3}}\"\n      }\n    },\n",
            ),
            text,
        )
        assertTrue("\"source\": {\n        \"kind\": \"manual\"\n      },\n      \"note\"" in text, text)
        val again = NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(hansWiki, hansManual, duke, shopKeeper)))
        assertEquals(emptyList(), again.written)
    }

    @Test
    fun `manual sorts before edit before wiki on the same tile`() {
        val wikiTwin = Entries.wiki("npc.hans", 3221, 3219, 0, 0, Entries.HANS_PAGE, Entries.HANS_MAP)
        val editTwin = NpcSpawnEntry("m0123456789ab", "npc.hans", 3221, 3219, 0, 0, null, NpcSpawnSource.Edit("2026-10-10T12:00:00Z"))
        val text = NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(wikiTwin, editTwin, hansManual)))
        assertTrue(text.indexOf("\"kind\": \"manual\"") < text.indexOf("\"kind\": \"edit\""))
        assertTrue(text.indexOf("\"kind\": \"edit\"") < text.indexOf("\"kind\": \"wiki\""))
    }

    @Test
    fun `empty and stale region files are deleted, the README is kept`() {
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(hansManual, Entries.manual("npc.man", 3264, 3232, 0, 8))))
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
        writeRaw(
            "12851.json",
            """{ "schemaVersion": 3, "regionId": 12851, "spawns": [ { "id": "x1", "npc": "hans", "x": 3212, "z": 3264, "height": 4, "walkRadius": -1, "source": "manual", "extra": 1 } ] }""",
        )
        writeRaw("12852.json", "{ not json")
        val errors = read().errors
        assertTrue(errors.any { it.startsWith("12850.json: spawns[0]: source.page must be a URL starting with https://oldschool.runescape.wiki/w/") }, errors.toString())
        listOf(
            "schemaVersion must be 2", "unknown field 'extra'", "id must match", "npc must be an RSCM name", "height must be an integer in 0..3",
            "walkRadius must be an integer", "source must be an object with a kind",
        ).forEach { expected -> assertTrue(errors.any { it.startsWith("12851.json:") && expected in it }, "missing '$expected' in $errors") }
        assertTrue(errors.any { it.startsWith("12852.json: not valid JSON") }, errors.toString())
    }

    @Test
    fun `missing ids, unknown kinds, origin and wiki ids without w are rejected`() {
        val text = NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual, duke, hansWiki, shopKeeper)))
            .replace("\"id\": \"${hansManual.id}\",\n", "")
            .replace("\"kind\": \"manual\"\n      },\n      \"note\"", "\"kind\": \"hand\"\n      },\n      \"note\"")
            .replace("\"id\": \"${hansWiki.id}\"", "\"id\": \"m${hansWiki.id.drop(1)}\"")
            .replace("\"direction\": \"SOUTH\"", "\"direction\": \"SOUTH\",\n      \"origin\": \"https://oldschool.runescape.wiki/w/Duke_Horacio\"")
            .replace("\"at\": \"2026-10-10T12:00:00Z\",\n", "")
        writeRaw("12850.json", text)
        val errors = read().errors
        listOf(
            "id must match", "source.kind must be one of wiki, manual, edit", "a wiki entry's id must start with 'w'",
            "'origin' was removed in schema 2", "source.at must be the instant the edit was made",
        ).forEach { expected -> assertTrue(errors.any { expected in it }, "missing '$expected' in $errors") }
    }

    @Test
    fun `schema 1 is only read by the migration`() {
        writeRaw("12850.json", """{ "schemaVersion": 1, "regionId": 12850, "spawns": [] }""")
        assertEquals(
            listOf("12850.json: schemaVersion 1 is no longer read; convert it with ./gradlew :alter-data:spawnSync -PspawnArgs=\"--migrate\""),
            read().errors,
        )
    }

    @Test
    fun `the same npc on the same tile twice is an error`() {
        writeRaw("12850.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual, hansManual.copy(id = "m000000000001", walkRadius = 3)))))
        assertEquals(listOf("duplicate spawn npc.hans at (3221, 3219, 0) in 12850.json"), read().errors)
    }

    @Test
    fun `the same id twice, even across files, is an error`() {
        writeRaw("12850.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(hansManual))))
        val moved = hansManual.copy(x = 3264)
        writeRaw("13106.json", NpcSpawnFiles.render(NpcSpawnFile(13106, listOf(moved))))
        assertEquals(listOf("duplicate id ${hansManual.id} in 12850.json, 13106.json"), read().errors)
    }

    @Test
    fun `CRLF files read the same as LF files`() {
        writeRaw("12850.json", NpcSpawnFiles.render(NpcSpawnFile(12850, listOf(duke))).replace("\n", "\r\n"))
        assertEquals(listOf(duke), read().entries)
        assertEquals(emptyList(), NpcSpawnFiles.write(dir, listOf(NpcSpawnFile(12850, listOf(duke)))).written)
    }
}
