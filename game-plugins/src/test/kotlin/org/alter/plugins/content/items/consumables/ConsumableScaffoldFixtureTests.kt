package org.alter.plugins.content.items.consumables

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Dev Cockpit's consumable scaffolds (the `.consumable.json` files in `dev-cockpit/src/test/resources/scaffold`, the exact
 * entries its generator writes) must be entries this parser accepts. Each fixture is wrapped in the real file's
 * schemaVersion, defaults, prayerGear and messages, so only the generated entries are under test.
 */
class ConsumableScaffoldFixtureTests {
    private val mapper = jacksonObjectMapper()
    private val fixtures: Path = Paths.get("../dev-cockpit/src/test/resources/scaffold")

    private fun parse(fixture: String): ConsumablesTable {
        val root = mapper.readTree(Files.readString(Paths.get("../data/cfg/consumables/consumables.json"))) as ObjectNode
        val wrapped = mapper.createObjectNode()
        listOf("schemaVersion", "defaults", "prayerGear", "messages").forEach { wrapped.set<ObjectNode>(it, root[it]) }
        wrapped.set<ObjectNode>("consumables", mapper.readTree(Files.readString(fixtures.resolve(fixture))))
        return Consumables.parse(wrapped.toString())
    }

    @Test
    fun `every scaffold fixture parses`() {
        val names = Files.list(fixtures).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".consumable.json") }.toList() }
        assertEquals(listOf("anchovies.consumable.json", "antivenom.consumable.json"), names.sorted())
        names.forEach { assertTrue(parse(it).consumables.isNotEmpty(), it) }
    }

    @Test
    fun `the anti-venom scaffold is a potion chain that cures and grants both immunities`() {
        val table = parse("antivenom.consumable.json")
        val four = table.byItem.getValue("item.antivenom4")
        assertEquals(Kind.POTION, four.kind)
        assertEquals(listOf(Effect.Antipoison(true, 1200), Effect.Antivenom(true, 60)), four.effects)
        assertEquals("item.antivenom3", four.replacement)
        assertEquals("item.vial", table.byItem.getValue("item.antivenom1").replacement)
    }

    @Test
    fun `the anchovies scaffold is a food that heals 1`() {
        val anchovies = parse("anchovies.consumable.json").byItem.getValue("item.anchovies")
        assertEquals(Kind.FOOD, anchovies.kind)
        assertEquals(Heal.Fixed(1, false), anchovies.heal)
        assertEquals(null, anchovies.replacement)
        assertEquals("https://oldschool.runescape.wiki/w/Anchovies", anchovies.source)
    }
}
