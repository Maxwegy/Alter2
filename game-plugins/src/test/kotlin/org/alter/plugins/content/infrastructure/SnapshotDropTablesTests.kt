package org.alter.plugins.content.infrastructure

import org.alter.game.model.weightedTableBuilder.rollTables
import org.alter.plugins.content.infrastructure.drops.DropDataService
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Converts the committed wiki snapshot's drop tables into loot tables, the way the server does. */
class SnapshotDropTablesTests {
    private val data = GameDataService(Paths.get("../data/cfg/wiki")).apply { load() }
    private val drops = DropDataService(data, Paths.get("build/no-overrides"))

    @Test
    fun `every snapshot drop table converts into valid loot tables`() {
        val ids = data.repository.npcIds()
        var withDrops = 0
        ids.forEach { id ->
            // LootTable's init checks that MAIN weights never exceed the table weight.
            if (drops.tables(id).isNotEmpty()) withDrops++
        }
        assertTrue(withDrops > 2_000, "only $withDrops NPCs have drop tables")
    }

    @Test
    fun `abyssal demon drops the whip at about 1 in 512`() {
        val tables = drops.tables(415)
        val random = Random(512)
        val kills = 400_000
        var whips = 0
        repeat(kills) { if (rollTables(tables, random).any { it.itemId == 4151 }) whips++ }
        val rate = whips.toDouble() / kills
        assertTrue(rate in (1.0 / 512) * 0.9..(1.0 / 512) * 1.1, "whip rate 1/${1 / rate}")
    }
}
