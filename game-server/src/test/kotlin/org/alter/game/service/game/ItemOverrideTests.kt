package org.alter.game.service.game

import dev.openrune.cache.filestore.definition.data.ItemType
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Item overrides must only change what they set; they used to reset every cache stat. */
class ItemOverrideTests {
    private val service = ItemMetadataService()
    private val mapper = ItemMetadataService.overrideMapper()

    private fun documents(file: String): List<ItemMetadataService.Metadata> =
        Files.readString(Paths.get("../data/cfg/items/itemOverrides", file))
            .split(Regex("(?m)^---\\s*$"))
            .filter { it.isNotBlank() }
            .map { mapper.readValue(it, ItemMetadataService.Metadata::class.java) }

    private fun cacheItem() = ItemType(id = 4708, name = "Ahrim's hood", examine = "Ahrim the Blighted's leather hood.", weight = 0.9).apply {
        bonuses = intArrayOf(0, 0, 0, 6, -2, 15, 13, 16, 6, 0, 0, 0, 0, 0)
        attackSpeed = 4
        renderAnimations = intArrayOf(808, 823, 819, 820, 821, 822, 824)
    }

    @Test
    fun `a skillReqs-only override keeps every cache stat`() {
        val override = documents("barrows/ahrim/ahrims_hood.yml").first()
        val item = cacheItem()
        val applied = service.applyOverride(item, override)

        assertEquals(setOf("name", "skillReqs"), applied)
        assertContentEquals(intArrayOf(0, 0, 0, 6, -2, 15, 13, 16, 6, 0, 0, 0, 0, 0), item.bonuses)
        assertEquals(4, item.attackSpeed)
        assertEquals("Ahrim the Blighted's leather hood.", item.examine)
        assertEquals(0.9, item.weight)
        assertContentEquals(intArrayOf(808, 823, 819, 820, 821, 822, 824), item.renderAnimations)
        assertEquals(70, item.skillReqs!![1].toInt())
        assertEquals(70, item.skillReqs!![6].toInt())
    }

    @Test
    fun `a full override applies every field, camelCase keys included`() {
        val override = documents("unique/35_excalibur.yml").single()
        val item = ItemType(id = 35, name = "Excalibur")
        val applied = service.applyOverride(item, override)

        assertEquals(5, item.attackSpeed)
        assertEquals(9, item.weaponType)
        assertEquals(3, item.equipSlot)
        assertEquals(9999, item.bonuses[0])
        assertEquals(25, item.bonuses[10])
        assertEquals(2.267, item.weight)
        assertContentEquals(intArrayOf(808, 823, 819, 820, 821, 822, 824), item.renderAnimations)
        assertEquals(true, "bonus.0" in applied && "renderAnimations" in applied)
    }

    @Test
    fun `snake_case keys are accepted too`() {
        val override = mapper.readValue(
            "id: 1\nequipment:\n  attack_speed: 6\n  melee_strength: 12\n",
            ItemMetadataService.Metadata::class.java,
        )
        val item = cacheItem()
        service.applyOverride(item, override)
        assertEquals(6, item.attackSpeed)
        assertEquals(12, item.bonuses[10])
        assertEquals(15, item.bonuses[5])
    }
}
