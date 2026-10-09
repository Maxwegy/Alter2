package org.alter.data.drops

import org.alter.data.FakeCacheView
import org.alter.data.config.InfraConfig
import org.alter.data.report.ReportBuilder
import org.alter.data.snapshot.DropTable
import org.alter.data.wiki.BucketRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Based on the live Abyssal demon#Standard rows captured on 2026-10-09. */
class DropTableMapperTests {
    private val cache = FakeCacheView(
        npcIds = setOf(415, 416),
        items = mapOf(
            22475 to "Abyssal ashes", 4151 to "Abyssal whip", 1307 to "Black sword", 7936 to "Pure essence",
            199 to "Grimy guam leaf", 995 to "Coins", 2722 to "Clue scroll (hard)", 23083 to "Brimstone key",
        ),
        noted = mapOf(7936 to 7937),
    )
    private val itemRows: List<BucketRow> = listOf(
        mapOf("page_name" to "Coins", "page_name_sub" to "Coins", "item_name" to "Coins", "item_id" to listOf("995"), "default_version" to true),
        mapOf("page_name" to "Coins", "page_name_sub" to "Coins (Shilo Village)", "item_name" to "Coins", "item_id" to listOf("617"), "default_version" to true),
        mapOf("page_name" to "Clue scroll (hard)", "page_name_sub" to "Clue scroll (hard)", "item_name" to "Clue scroll (hard)", "item_id" to listOf("N/A")),
    )
    private val monsterRows: List<BucketRow> = listOf(
        mapOf("page_name" to "Abyssal demon", "page_name_sub" to "Abyssal demon#Standard", "id" to listOf("415", "416")),
        mapOf("page_name" to "Abyssal demon", "page_name_sub" to "Abyssal demon#Wilderness Slayer Cave", "id" to listOf("11239")),
    )

    private fun line(item: String, rarity: String, quantity: String = "1", low: Int = 1, high: Int = 1, rolls: Int = 1, source: String = "Abyssal demon#Standard"): BucketRow =
        mapOf(
            "page_name" to source.substringBefore('#'),
            "page_name_sub" to source,
            "item_name" to item,
            "drop_json" to """{"Rarity":"$rarity","Drop type":"combat","Drop Quantity":"$quantity","Quantity Low":$low,"Quantity High":$high,"Rolls":$rolls,"Dropped item":"$item"}""",
        )

    private fun map(vararg lines: BucketRow, report: ReportBuilder = ReportBuilder("test")): DropTable {
        val mapper = DropTableMapper(cache, ItemNameResolver(itemRows, cache), InfraConfig.DEFAULT_TERTIARY_PATTERNS, pets = emptyList())
        return mapper.map(lines.toList(), monsterRows, report).single().tables.single()
    }

    @Test
    fun `classifies always, main and tertiary lines with exact chances`() {
        val table = map(
            line("Abyssal ashes", "Always"),
            line("Abyssal whip", "1/512"),
            line("Black sword", "4/128"),
            line("Grimy guam leaf", "1/26.9"),
            line("Clue scroll (hard)", "1/128"),
            line("Brimstone key", "1/76"),
        )
        assertEquals(listOf(415, 416), table.npcIds)
        assertFalse(table.needsReview)
        assertEquals(listOf(22475), table.always.map { it.item })
        assertEquals(mapOf(199 to listOf(10L, 269L), 1307 to listOf(1L, 32L), 4151 to listOf(1L, 512L)), table.main.associate { it.item to it.chance })
        assertEquals(setOf(2722, 23083), table.tertiary.map { it.item }.toSet())
        assertEquals("1/512", table.main.single { it.item == 4151 }.wiki)
    }

    @Test
    fun `coins resolve to the default page, not a lookalike`() {
        val table = map(line("Coins", "35/128", quantity = "132", low = 132, high = 132))
        assertEquals(995, table.main.single().item)
        assertEquals(132 to 132, table.main.single().let { it.min to it.max })
    }

    @Test
    fun `noted drops use the noted id`() {
        val drop = map(line("Pure essence", "5/128", quantity = "60 (noted)", low = 60, high = 60)).main.single()
        assertEquals(7937, drop.item)
        assertTrue(drop.noted)
    }

    @Test
    fun `overfull main tables are marked for review and rolled independently`() {
        val table = map(line("Black sword", "100/128"), line("Abyssal whip", "64/128"))
        assertTrue(table.needsReview)
        assertTrue(table.main.isEmpty())
        assertEquals(2, table.tertiary.size)
    }

    @Test
    fun `unparseable lines are skipped and reported`() {
        val report = ReportBuilder("test")
        val table = map(line("Black sword", "Rare"), line("Mystery item", "1/8"), line("Abyssal whip", "1/512"), report = report)
        assertEquals(listOf(4151), table.main.map { it.item })
        assertEquals(1, report.count(DropTableMapper.SECTION_UNPARSEABLE_RARITY))
        assertEquals(1, report.count(DropTableMapper.SECTION_UNRESOLVED_ITEM))
    }

    @Test
    fun `sources without npcs in our cache are skipped`() {
        val report = ReportBuilder("test")
        val mapper = DropTableMapper(cache, ItemNameResolver(itemRows, cache), emptyList(), emptyList())
        val pages = mapper.map(listOf(line("Abyssal whip", "1/512", source = "Abyssal demon#Wilderness Slayer Cave")), monsterRows, report)
        assertTrue(pages.isEmpty())
        assertEquals(1, report.count(DropTableMapper.SECTION_UNMATCHED_SOURCE))
    }
}
