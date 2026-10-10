package org.alter.plugins.content.infrastructure.spawns

import org.alter.data.spawns.NpcSpawnFiles
import org.alter.data.spawns.NpcSpawnSource
import org.alter.data.spawns.SpawnIds
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `data/cfg/spawns/npcs` is committed data the server trusts at boot; these checks keep it loadable. */
class NpcSpawnDataTests {
    private val dir = Paths.get("../data/cfg/spawns/npcs")

    /** `name:id` lines of the committed RSCM table, in file order, so the check needs no cache. */
    private val rscm: List<Pair<String, Int>> = Files.readAllLines(Paths.get("../data/cfg/rscm/npc.rscm"))
        .map { it.split(':') }.filter { it.size == 2 }.mapNotNull { (name, id) -> id.trim().toIntOrNull()?.let { "npc." + name.trim() to it } }
    private val idByName: Map<String, Int> = rscm.toMap()

    /** The first name per id is the canonical (gameval) name; later ones are aliases. */
    private val canonicalById: Map<Int, String> = rscm.reversed().associate { (name, id) -> id to name }

    private val read = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(dir))

    @Test
    fun `every file parses, its name is its regionId and every entry lies in that region`() {
        assertEquals(emptyList(), read.errors)
        assertTrue(read.files.isNotEmpty())
        read.files.forEach { file ->
            assertEquals(NpcSpawnFiles.SCHEMA_VERSION, file.schemaVersion)
            assertTrue(Files.exists(dir.resolve("${file.regionId}.json")))
            file.spawns.forEach { assertEquals(file.regionId, it.regionId, "${it.npc} at (${it.x}, ${it.z})") }
        }
    }

    @Test
    fun `files are in the canonical form the writer produces`() {
        read.files.forEach { file ->
            val text = Files.readString(dir.resolve("${file.regionId}.json")).replace("\r\n", "\n")
            assertEquals(NpcSpawnFiles.render(file), text, "${file.regionId}.json is not canonical")
        }
    }

    @Test
    fun `names are canonical RSCM names and the engine checks pass`() {
        val notCanonical = read.entries.filter { e -> idByName[e.npc]?.let { canonicalById[it] } != e.npc }.map { it.npc }.distinct()
        assertEquals(emptyList(), notCanonical, "unknown names or aliases; use the first name per id in npc.rscm")
        // Height, walk radius and the wiki URL prefix are checked by the reader; ids, directions and per-id duplicates here.
        assertIs<NpcSpawnsLoader.Result.Loaded>(NpcSpawnsLoader { idByName[it] }.load(dir))
    }

    @Test
    fun `wiki sources point at the OSRS wiki`() {
        read.entries.mapNotNull { it.source as? NpcSpawnSource.Wiki }.forEach { assertTrue(it.page.startsWith(NpcSpawnFiles.WIKI_PAGE_PREFIX), it.page) }
    }

    @Test
    fun `every entry carries the id its kind derives`() {
        // Wiki: SpawnIds.wiki(page, npc, x, z, height). Manual: minted once by spawnSync --migrate with the
        // "schema1" salt. Edit entries keep the id of the entry they came from; none are committed yet.
        val wrong = read.entries.filter { e ->
            val expected = when (val s = e.source) {
                is NpcSpawnSource.Wiki -> SpawnIds.wiki(s.page, e.npc, e.x, e.z, e.height)
                NpcSpawnSource.Manual -> SpawnIds.minted(e.npc, e.x, e.z, e.height, SpawnIds.MIGRATION_SALT)
                is NpcSpawnSource.Edit -> e.id
            }
            e.id != expected
        }.map { "${it.id} ${it.npc} at (${it.x}, ${it.z}, ${it.height})" }
        assertEquals(emptyList(), wrong)
        assertTrue(read.entries.all { SpawnIds.isValid(it.id) })
        assertEquals(read.entries.size, read.entries.map { it.id }.toSet().size)
        assertEquals(0, read.entries.count { it.source is NpcSpawnSource.Edit })
    }

    @Test
    fun `all 115 hand-written spawns were migrated as manual entries`() {
        // 43 lumbridge/spawns/SpawnPlugin, 15 ChatSpawnsPlugin, 12 thieving-test SpawnPlugin, 14 in Lumbridge NPC
        // plugins, 30 in the Barrows brothers' plugins and 1 King Black Dragon: every spawnNpc call before Phase 2.
        val manual = read.entries.count { it.source is NpcSpawnSource.Manual }
        assertEquals(115, manual)
    }

    @Test
    fun `the first spawnSync run added 4291 wiki entries`() {
        // ./gradlew :alter-data:spawnSync on 2026-10-10 (cache 241): 3213 pages, 4291 wiki entries, 21 dropped for a
        // manual entry. Update these numbers with every committed spawnSync run.
        val wiki = read.entries.count { it.source is NpcSpawnSource.Wiki }
        assertEquals(4291, wiki)
        assertEquals(115 + 4291, read.entries.size)
    }
}
