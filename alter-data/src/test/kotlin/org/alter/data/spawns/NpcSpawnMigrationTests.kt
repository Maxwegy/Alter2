package org.alter.data.spawns

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NpcSpawnMigrationTests {
    private val dir: Path = Files.createTempDirectory("spawn-migrate")

    /** Schema-1 region 12850 exactly as the Phase 2 writer produced it. */
    private val schema1 = """
        {
          "schemaVersion": 1,
          "regionId": 12850,
          "spawns": [
            {
              "npc": "npc.hans",
              "x": 3212,
              "z": 3219,
              "height": 0,
              "walkRadius": 11,
              "source": {
                "page": "https://oldschool.runescape.wiki/w/Hans",
                "map": "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}"
              }
            },
            {
              "npc": "npc.hans",
              "x": 3221,
              "z": 3219,
              "height": 0,
              "walkRadius": 0,
              "direction": "EAST",
              "source": "manual",
              "note": "migrated from content/areas/lumbridge/npcs/HansPlugin.kt"
            }
          ]
        }
    """.trimIndent() + "\n"

    private fun files() = Files.list(dir).use { s -> s.toList() }.sorted().associate { it.fileName.toString() to Files.readString(it) }

    @Test
    fun `schema 1 becomes schema 2 with derived ids`() {
        Files.writeString(dir.resolve("12850.json"), schema1)
        val result = assertIs<NpcSpawnMigration.Result.Migrated>(NpcSpawnMigration.run(dir))
        assertEquals(1, result.manual)
        assertEquals(1, result.wiki)
        assertEquals(listOf("12850.json"), result.write.written)
        val entries = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(dir)).also { assertEquals(emptyList(), it.errors) }.entries
        assertEquals(
            listOf(
                NpcSpawnEntry("w06bd7d9b22e4", "npc.hans", 3212, 3219, 0, 11, null, NpcSpawnSource.Wiki(Entries.HANS_PAGE, Entries.HANS_MAP)),
                NpcSpawnEntry("m56d8a069f0b8", "npc.hans", 3221, 3219, 0, 0, "EAST", NpcSpawnSource.Manual, "migrated from content/areas/lumbridge/npcs/HansPlugin.kt"),
            ),
            entries,
        )
    }

    @Test
    fun `a second run is already schema 2 and writes nothing`() {
        Files.writeString(dir.resolve("12850.json"), schema1)
        assertIs<NpcSpawnMigration.Result.Migrated>(NpcSpawnMigration.run(dir))
        val before = files()
        val again = assertIs<NpcSpawnMigration.Result.AlreadyMigrated>(NpcSpawnMigration.run(dir))
        assertTrue(again.report.sections.any { "already schema 2" in it.items })
        assertEquals(before, files())
    }

    @Test
    fun `an entry with origin rejects the whole run`() {
        Files.writeString(dir.resolve("12850.json"), schema1)
        val withOrigin = schema1.replace("12850", "13106").replace("3221", "3264").replace("\"source\": \"manual\",", "\"source\": \"manual\",\n      \"origin\": \"https://oldschool.runescape.wiki/w/Hans\",")
            .replace("3212", "3265")
        Files.writeString(dir.resolve("13106.json"), withOrigin)
        val before = files()
        val result = assertIs<NpcSpawnMigration.Result.Rejected>(NpcSpawnMigration.run(dir))
        val problems = result.report.sections.single { it.title == "Problems" }.items
        assertEquals(listOf("13106.json: spawns[1]: origin is not migrated automatically; convert by hand"), problems)
        assertEquals(before, files())
    }

    @Test
    fun `a broken schema-1 entry rejects the run`() {
        Files.writeString(dir.resolve("12850.json"), schema1.replace("\"source\": \"manual\"", "\"source\": \"hand\""))
        val result = assertIs<NpcSpawnMigration.Result.Rejected>(NpcSpawnMigration.run(dir))
        assertTrue(result.report.sections.single { it.title == "Problems" }.items.single().endsWith("source must be \"manual\" or { \"page\", \"map\" }"))
    }
}
