package org.alter.plugins.content.infrastructure.spawns

import org.alter.data.spawns.NpcSpawnEntry
import org.alter.data.spawns.NpcSpawnFile
import org.alter.data.spawns.NpcSpawnFiles
import org.alter.data.spawns.NpcSpawnSource
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NpcSpawnsLoaderTests {
    private val dir: Path = Files.createTempDirectory("npc-spawns")

    /** A stand-in for RSCM: the real names below, nothing else (npc.man is deliberately unknown here). */
    private val names = mapOf("npc.hans" to 3105, "npc.duke_of_lumbridge" to 815, "npc.duke_horacio" to 815)
    private val loader = NpcSpawnsLoader { names[it] }

    private val hans = NpcSpawnEntry("npc.hans", 3221, 3219, 0, 0, "EAST", NpcSpawnSource.Manual)
    private val duke = NpcSpawnEntry("npc.duke_of_lumbridge", 3212, 3220, 1, 4, null, NpcSpawnSource.Manual)
    private val wikiHans = NpcSpawnEntry(
        "npc.hans", 3212, 3219, 0, 11, null,
        NpcSpawnSource.Wiki("https://oldschool.runescape.wiki/w/Hans", "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}"),
    )

    private fun write(vararg entries: NpcSpawnEntry) = NpcSpawnFiles.write(dir, NpcSpawnFiles.group(entries.toList()))

    @Test
    fun `valid files load with ids, directions and counts`() {
        write(hans, duke, wikiHans, NpcSpawnEntry("npc.hans", 3264, 3232, 0, 0, null, NpcSpawnSource.Manual))
        val loaded = assertIs<NpcSpawnsLoader.Result.Loaded>(loader.load(dir))
        assertEquals(4, loaded.spawns.size)
        assertEquals(2, loaded.fileCount)
        assertEquals(3, loaded.manual)
        assertEquals(1, loaded.wiki)
        val byTile = loaded.index
        assertEquals(Direction.EAST, byTile.getValue(NpcSpawnsLoader.Key(3105, Tile(3221, 3219, 0))).direction)
        val dukeSpawn = byTile.getValue(NpcSpawnsLoader.Key(815, Tile(3212, 3220, 1)))
        assertEquals(Direction.SOUTH, dukeSpawn.direction) // absent direction: the spawnNpc default
        assertEquals(4, dukeSpawn.entry.walkRadius)
    }

    @Test
    fun `a missing directory is Missing`() {
        assertEquals(NpcSpawnsLoader.Result.Missing, loader.load(dir.resolve("absent")))
    }

    @Test
    fun `unknown names, bad directions and file errors are all collected`() {
        write(hans.copy(direction = "UP"), duke.copy(npc = "npc.man"), duke.copy(direction = "NONE", z = 3221))
        Files.writeString(dir.resolve("13106.json"), "{ not json")
        val failed = assertIs<NpcSpawnsLoader.Result.Failed>(loader.load(dir))
        assertEquals(4, failed.errors.size, failed.errors.toString())
        assertEquals(1, failed.errors.count { it.startsWith("13106.json: not valid JSON") })
        assertEquals(1, failed.errors.count { "npc.man is not in data/cfg/rscm/npc.rscm" in it })
        assertEquals(1, failed.errors.count { "direction 'UP'" in it })
        assertEquals(1, failed.errors.count { "direction 'NONE'" in it })
    }

    @Test
    fun `an alias and its canonical name on the same tile are a duplicate`() {
        write(duke, duke.copy(npc = "npc.duke_horacio"))
        val failed = assertIs<NpcSpawnsLoader.Result.Failed>(loader.load(dir))
        assertEquals(listOf("npc id 815 spawns twice at (3212, 3220, 1): npc.duke_horacio, npc.duke_of_lumbridge"), failed.errors)
    }
}
