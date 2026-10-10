package org.alter.data.spawns

import org.alter.data.FakeCacheView
import org.alter.data.cache.CacheView
import org.alter.data.report.ReportBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpawnGeneratorTests {
    private val ids = setOf(3105, 2813, 276, 277, 278, 279, 280, 6823, 10887, 815, 5327, 8051, 11024)
    private val cache = FakeCacheView(npcIds = ids)
    private val pages = listOf(
        SpawnGenerator.Page("Hans", Fixtures.hans),
        SpawnGenerator.Page("Shop keeper (Lumbridge)", Fixtures.shopKeeper),
        SpawnGenerator.Page("Duke Horacio", Fixtures.duke),
        SpawnGenerator.Page("Town Crier", Fixtures.townCrier),
    )

    private fun generate(existing: List<NpcSpawnEntry> = emptyList(), pages: List<SpawnGenerator.Page> = this.pages, cache: CacheView = this.cache) =
        SpawnGenerator(cache).generate(pages, existing, ReportBuilder("test"))

    private fun wikiOnly(r: SpawnGenerator.Result) = r.entries.filter { it.source is NpcSpawnSource.Wiki }

    @Test
    fun `Hans, Shop keeper, Duke and Town Crier give nine entries`() {
        val r = generate()
        assertEquals(9, r.entries.size)
        assertEquals(9, r.wikiEntries)
        assertEquals(0, r.manualKept)
        assertEquals(1, r.skips["ambiguousIds"])
        assertEquals(1, r.skips["unsupportedMtype.polygon"])
        assertEquals(0, r.skips["noMapTemplate"])
        assertEquals(10, r.versionPairs)

        val hans = r.entries.single { it.npc == "npc.npc_3105" }
        assertEquals(NpcSpawnEntry("w7f6f9a4f87ff", "npc.npc_3105", 3212, 3219, 0, 11, source = NpcSpawnSource.Wiki("https://oldschool.runescape.wiki/w/Hans", "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}")), hans)
        val shop = r.entries.single { it.npc == "npc.npc_2813" }
        assertEquals(Triple(3211, 3247, 0), Triple(shop.x, shop.z, shop.walkRadius))
        assertEquals("https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)", (shop.source as NpcSpawnSource.Wiki).page)
        val crier = r.entries.single { it.npc == "npc.npc_276" }
        assertEquals(Triple(3254, 3428, 4), Triple(crier.x, crier.z, crier.walkRadius))
        assertEquals(12853, crier.regionId)
        assertEquals(setOf(276, 277, 278, 279, 280, 6823, 10887).map { "npc.npc_$it" }.toSet(), r.entries.filter { "Town_Crier" in (it.source as NpcSpawnSource.Wiki).page }.map { it.npc }.toSet())
    }

    @Test
    fun `a manual entry within the walk radius drops the wiki entry for the same npc`() {
        val manual = Entries.manual("npc.npc_3105", 3210, 3221, 0, 3)
        val r = generate(listOf(manual))
        assertEquals(1, r.manualKept)
        assertEquals(1, r.droppedOverlappedByManual)
        assertEquals(8, r.wikiEntries)
        assertEquals(listOf(manual), r.entries.filter { it.npc == "npc.npc_3105" })
        // Same tile, other height: not an overlap.
        val upstairs = generate(listOf(Entries.manual("npc.npc_3105", 3210, 3221, 1, 3)))
        assertEquals(0, upstairs.droppedOverlappedByManual)
        assertEquals(9, upstairs.wikiEntries)
    }

    @Test
    fun `the same npc in the region but out of reach is kept and reported`() {
        val manual = Entries.manual("npc.npc_2813", 3230, 3210, 0, 2)
        val r = generate(listOf(manual))
        assertEquals(0, r.droppedOverlappedByManual)
        assertEquals(1, r.possibleDuplicates)
        assertEquals(9, r.wikiEntries)
    }

    @Test
    fun `exact wiki duplicates collapse into one`() {
        val r = generate(pages = pages + SpawnGenerator.Page("Shop keeper (copy)", Fixtures.shopKeeper))
        assertEquals(1, r.wikiDuplicatesCollapsed)
        val shop = r.entries.single { it.npc == "npc.npc_2813" }
        assertEquals("https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)", (shop.source as NpcSpawnSource.Wiki).page)
    }

    @Test
    fun `id problems are counted by reason`() {
        val noCrier = generate(cache = FakeCacheView(npcIds = setOf(3105, 2813)))
        assertEquals(7, noCrier.skips["idNotInCache"])
        val noNames = generate(cache = object : CacheView by cache {
            override fun rscmName(table: String, id: Int): String? = null
        })
        assertEquals(9, noNames.skips["noRscmName"])
        assertEquals(0, noNames.entries.size)
        val missing = generate(pages = listOf(SpawnGenerator.Page("Gone", null), SpawnGenerator.Page("Plain", "No infobox here.")))
        assertEquals(1, missing.skips["pageMissing"])
        assertEquals(1, missing.skips["noInfobox"])
    }

    @Test
    fun `stale wiki entries are removed, manual ones kept, and a second run writes the same bytes`() {
        val dir = Files.createTempDirectory("spawn-gen")
        val manual = Entries.manual("npc.npc_1", 3221, 3219, 0, 0, "EAST", note = "migrated")
        val stale = Entries.wiki("npc.npc_9", 2600, 3100, 0, 0, "https://oldschool.runescape.wiki/w/Old", "{{Map|2600,3100|mtype=pin}}")
        NpcSpawnFiles.write(dir, NpcSpawnFiles.group(listOf(manual, stale)))
        assertTrue(Files.exists(dir.resolve("${stale.regionId}.json")))

        fun run(): NpcSpawnFiles.WriteResult {
            val existing = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(dir)).entries
            return NpcSpawnFiles.write(dir, NpcSpawnFiles.group(generate(existing).entries))
        }
        val first = run()
        assertEquals(listOf("${stale.regionId}.json"), first.removed)
        assertFalse(Files.exists(dir.resolve("${stale.regionId}.json")))
        val read = assertIs<NpcSpawnFiles.ReadResult.Read>(NpcSpawnFiles.readAll(dir))
        assertEquals(emptyList(), read.errors)
        assertEquals(listOf(manual), read.entries.filter { it.source is NpcSpawnSource.Manual })
        assertEquals(10, read.entries.size)

        val bytes = Files.list(dir).use { s -> s.toList() }.sorted().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
        val second = run()
        assertEquals(emptyList(), second.written)
        assertEquals(emptyList(), second.removed)
        assertEquals(bytes, Files.list(dir).use { s -> s.toList() }.sorted().associate { it.fileName.toString() to Files.readAllBytes(it).toList() })
    }

    @Test
    fun `a lower-case map template is stored with the canonical name`() {
        val page = SpawnGenerator.Page("Hut", "{{Infobox NPC\n|map = {{map|x=3523|y=3177|mtype=pin|group=hut}}\n|id = 2813\n}}")
        val entry = generate(pages = listOf(page)).entries.single()
        assertEquals("{{Map|x=3523|y=3177|mtype=pin|group=hut}}", (entry.source as NpcSpawnSource.Wiki).map)
        val errors = mutableListOf<String>()
        NpcSpawnFiles.group(listOf(entry)).forEach { NpcSpawnFiles.parse("${it.regionId}.json", NpcSpawnFiles.render(it), errors) }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `page urls use underscores`() {
        assertEquals("https://oldschool.runescape.wiki/w/Shop_keeper_(Lumbridge)", SpawnGenerator.pageUrl("Shop keeper (Lumbridge)"))
        assertEquals("https://oldschool.runescape.wiki/w/Seers'_Village", SpawnGenerator.pageUrl("Seers' Village"))
        assertEquals("https://oldschool.runescape.wiki/w/What%3F", SpawnGenerator.pageUrl("What?"))
    }
}
