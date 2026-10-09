package org.alter.game.model.weightedTableBuilder

import org.alter.rscm.RSCM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LootTableTests {
    private val a = Loot(item = 1, min = 1, weight = 1)
    private val b = Loot(item = 2, min = 1, weight = 2)

    @Test
    fun `main roll picks exactly by weight with an empty remainder`() {
        val table = LootTable(TableType.MAIN, tableWeight = 4, drops = linkedSetOf(a, b))
        // Exhaustive: rolls 0..3 map to A, B, B, nothing. The old roller gave the first entry an extra slot.
        assertEquals(listOf(a, b, b, null), (0 until 4).map(table::pickMain))
    }

    @Test
    fun `main table without a table weight uses the sum`() {
        val table = LootTable(TableType.MAIN, tableWeight = 0, drops = linkedSetOf(a, b))
        assertEquals(3, table.total)
        assertNull(LootTable(TableType.MAIN, tableWeight = 0, drops = linkedSetOf()).pickMain(0))
    }

    @Test
    fun `main weights may not exceed the table weight`() {
        assertFailsWith<IllegalStateException> { LootTable(TableType.MAIN, tableWeight = 2, drops = linkedSetOf(a, b)) }
    }

    @Test
    fun `main roll frequencies match the weights`() {
        val table = LootTable(TableType.MAIN, tableWeight = 8, drops = linkedSetOf(a, b))
        val random = Random(42)
        val counts = IntArray(3)
        repeat(80_000) { counts[rollTables(listOf(table), random).singleOrNull()?.itemId ?: 0]++ }
        assertTrue(counts[0] in 49_000..51_000, "nothing ${counts[0]}") // 5/8
        assertTrue(counts[1] in 9_400..10_600, "a ${counts[1]}") // 1/8
        assertTrue(counts[2] in 19_200..20_800, "b ${counts[2]}") // 2/8
    }

    @Test
    fun `always tables drop everything and rolls repeat`() {
        val table = LootTable(TableType.ALWAYS, drops = linkedSetOf(a, b), rolls = 2)
        assertEquals(listOf(1, 2, 1, 2), rollTables(listOf(table)).map { it.itemId })
    }

    @Test
    fun `tertiary entries roll independently`() {
        val certain = Loot(item = 3, min = 1, weight = 10)
        val never = Loot(item = 4, min = 1, weight = 0)
        val table = LootTable(TableType.TERTIARY, tableWeight = 10, drops = linkedSetOf(certain, never))
        assertEquals(listOf(3), rollTables(listOf(table), Random(1)).map { it.itemId })
    }

    @Test
    fun `a pre-roll hit skips the main tables`() {
        val pre = LootTable(TableType.PRE_ROLL, tableWeight = 1, drops = linkedSetOf(Loot(item = 9, min = 1, weight = 1)))
        val main = LootTable(TableType.MAIN, tableWeight = 1, drops = linkedSetOf(Loot(item = 8, min = 1, weight = 1)))
        assertEquals(listOf(9), rollTables(listOf(pre, main)).map { it.itemId })
    }

    @Test
    fun `every table of a type is rolled`() {
        val first = LootTable(TableType.MAIN, tableWeight = 1, drops = linkedSetOf(Loot(item = 5, min = 1, weight = 1)))
        val second = LootTable(TableType.MAIN, tableWeight = 1, drops = linkedSetOf(Loot(item = 6, min = 1, weight = 1)))
        assertEquals(listOf(5, 6), rollTables(listOf(first, second)).map { it.itemId })
    }

    @Test
    fun `null items are nothing and filters exclude loot`() {
        val table = LootTable(TableType.ALWAYS, drops = linkedSetOf(Loot(item = null, min = 1), a, b))
        assertEquals(listOf(2), rollTables(listOf(table), filter = { it.item != 1 }).map { it.itemId })
    }

    @Test
    fun `RSCM names resolve to ids`() {
        RSCM.init() // reads the committed ../data/cfg/rscm
        val table = LootTable(TableType.ALWAYS, drops = linkedSetOf(Loot(item = "item.abyssal_whip", min = 1)))
        assertEquals(4151, rollTables(listOf(table)).single().itemId)
    }

    @Test
    fun `quantities stay within min and max`() {
        val table = LootTable(TableType.ALWAYS, drops = linkedSetOf(Loot(item = 995, min = 10, max = 20)))
        val random = Random(7)
        repeat(200) { assertTrue(rollTables(listOf(table), random).single().amount in 10..20) }
    }
}
