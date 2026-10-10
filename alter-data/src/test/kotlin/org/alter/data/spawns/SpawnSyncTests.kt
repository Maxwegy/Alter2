package org.alter.data.spawns

import kotlinx.coroutines.runBlocking
import org.alter.data.FakeCacheView
import org.alter.data.config.InfraConfig
import org.alter.data.wiki.RawCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpawnSyncTests {
    private val root: Path = Files.createTempDirectory("spawn-sync")
    private val rawCache = RawCache(root.resolve("wiki-cache"))
    private val spawnDir: Path = root.resolve("cfg/spawns/npcs")
    private val cache = FakeCacheView(npcIds = setOf(3105, 2813, 276, 277, 278, 279, 280, 6823, 10887))

    private fun sync() = SpawnSync(null, null, rawCache, cache, InfraConfig.Wiki(), spawnDir)

    private fun seedCache(pages: Map<String, String>) {
        val now = Instant.now().toString()
        rawCache.write(RawCache.Entry(SpawnSync.EMBEDDED_IN, "test", now, (pages.keys + "Lumbridge").map { mapOf("title" to it) }))
        rawCache.write(RawCache.Entry("map_infobox_npc", "test", now, pages.keys.map { mapOf("page_name" to it) }))
        rawCache.write(RawCache.Entry("map_infobox_monster", "test", now, listOf(mapOf("page_name" to "Man"))))
        rawCache.write(RawCache.Entry(SpawnSync.PAGES, "test", now, pages.map { (t, w) -> mapOf("title" to t, "wikitext" to w) }))
    }

    @Test
    fun `offline run writes the region files and a second run changes nothing`() = runBlocking {
        seedCache(mapOf("Hans" to Fixtures.hans, "Town Crier" to Fixtures.townCrier))
        val first = assertIs<SpawnSync.Result.Written>(sync().run(SpawnSync.Options(offline = true)))
        assertEquals(8, first.generated.wikiEntries)
        assertTrue(first.write.written.isNotEmpty())
        assertEquals(2, first.report.summary["pages.candidates"])
        assertEquals(0, first.report.summary["skip.noMapTemplate"])
        assertEquals(8, first.report.summary["entries"])
        listOf("droppedClaimedById", "orphanedEdits", "editsKept", "manualKept").forEach { assertEquals(0, first.report.summary[it], it) }

        val second = assertIs<SpawnSync.Result.Written>(sync().run(SpawnSync.Options(offline = true)))
        assertEquals(emptyList(), second.write.written)
        assertEquals(emptyList(), second.write.removed)
    }

    @Test
    fun `an edit entry claiming a wiki id survives the sync and is counted`() = runBlocking {
        seedCache(mapOf("Hans" to Fixtures.hans, "Town Crier" to Fixtures.townCrier))
        val edited = NpcSpawnEntry("w7f6f9a4f87ff", "npc.npc_3105", 3214, 3220, 0, 3, source = NpcSpawnSource.Edit("2026-10-10T12:00:00Z", Entries.HANS_PAGE, Entries.HANS_MAP))
        NpcSpawnFiles.write(spawnDir, NpcSpawnFiles.group(listOf(edited)))
        val result = assertIs<SpawnSync.Result.Written>(sync().run(SpawnSync.Options(offline = true)))
        assertEquals(1, result.report.summary["droppedClaimedById"])
        assertEquals(1, result.report.summary["editsKept"])
        assertEquals(0, result.report.summary["orphanedEdits"])
        assertEquals(7, result.report.summary["wikiEntries"])
        val read = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(spawnDir))
        assertEquals(listOf(edited), read.entries.filter { it.npc == "npc.npc_3105" })
    }

    @Test
    fun `offline without a raw cache is rejected and writes nothing`() = runBlocking {
        val result = assertIs<SpawnSync.Result.Rejected>(sync().run(SpawnSync.Options(offline = true)))
        assertTrue("no raw cache" in result.reason, result.reason)
        assertTrue(!Files.exists(spawnDir))
    }

    @Test
    fun `zero entries are rejected`() = runBlocking {
        seedCache(mapOf("Man" to Fixtures.man))
        val result = assertIs<SpawnSync.Result.Rejected>(sync().run(SpawnSync.Options(offline = true)))
        assertTrue("zero" in result.reason, result.reason)
    }

    @Test
    fun `broken region files stop the sync`() = runBlocking {
        seedCache(mapOf("Hans" to Fixtures.hans))
        Files.createDirectories(spawnDir)
        Files.writeString(spawnDir.resolve("12850.json"), "{ not json")
        assertIs<SpawnSync.Result.Rejected>(sync().run(SpawnSync.Options(offline = true)))
        assertEquals("{ not json", Files.readString(spawnDir.resolve("12850.json")))
    }

    @Test
    fun `the server cannot reach spawnSync and wikiSync does not run it`() {
        val serverSources = listOf("../game-server/src/main", "../game-plugins/src/main", "../game-api/src/main").map(Paths::get).filter(Files::exists)
        assertTrue(serverSources.isNotEmpty())
        val users = serverSources.flatMap { dir -> Files.walk(dir).use { s -> s.filter { it.extension == "kt" }.toList() } }
            .filter { file -> file.readText().let { "SpawnSync" in it || "SpawnGenerator" in it } }
        assertEquals(emptyList(), users, "server code must not run the spawn generator")
        val wiki = Files.walk(Paths.get("src/main/kotlin/org/alter/data/wiki")).use { s -> s.filter { it.extension == "kt" }.toList() }
        assertTrue(wiki.none { "org.alter.data.spawns" in it.readText() }, "WikiSync must not depend on the spawn generator")
    }
}
