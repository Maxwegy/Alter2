package org.alter.plugins.content.skills.resources

import org.alter.plugins.content.skills.resources.ResourceRules.Outcome
import org.alter.plugins.content.skills.resources.ResourceRules.Refusal
import org.alter.plugins.content.skills.resources.ResourceRules.Rolls
import org.alter.plugins.content.skills.resources.ResourceRules.Start
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [ResourceRules] against the committed file's sourced values. */
class ResourceRulesTests {
    private val table = ResourceNodeDefs.load(Paths.get("../data/cfg/resources/resource_nodes.json"))
    private val nodes = table.nodes.associateBy { it.id }
    private val wc = table.skills.getValue("woodcutting")
    private val mining = table.skills.getValue("mining")

    private fun tool(skill: SkillDef, item: String) = skill.tools.first { it.item == "item.$item" }

    private fun firstLevelAt256(low: Int, high: Int) = (1..99).first { ResourceRules.chance256(low, high, it) == 256 }

    @Test
    fun `chance256 matches the wiki formula`() {
        assertEquals(80, ResourceRules.chance256(48, 90, 74))
        assertEquals(65, ResourceRules.chance256(64, 200, 1))
        assertEquals(201, ResourceRules.chance256(64, 200, 99))
        assertEquals(33, ResourceRules.chance256(32, 100, 1))
        assertEquals(101, ResourceRules.chance256(32, 100, 99))
        assertEquals(17, ResourceRules.chance256(16, 100, 1))
        assertEquals(101, ResourceRules.chance256(16, 100, 99))
        assertEquals(101, ResourceRules.chance256(100, 350, 1))
        assertEquals(97, ResourceRules.chance256(96, 350, 1))
        assertEquals(256, ResourceRules.chance256(96, 350, 99))
        assertEquals(62, firstLevelAt256(100, 350), "copper")
        assertEquals(63, firstLevelAt256(96, 350), "iron")
        assertEquals(78, firstLevelAt256(96, 300), "tree with an iron axe")
        assertEquals(ResourceRules.chance256(64, 200, 99), ResourceRules.chance256(64, 200, 105))
    }

    @Test
    fun `start checks level, tool, tool level and inventory in that order`() {
        val oak = nodes.getValue("oak")
        val rune = setOf("item.rune_axe")
        assertEquals(Start.Refused(Refusal.LEVEL_TOO_LOW), ResourceRules.start(oak, wc, 14, rune, false, false))
        assertEquals(Start.Refused(Refusal.NO_TOOL), ResourceRules.start(oak, wc, 40, emptySet(), false, false))
        assertEquals(Start.Refused(Refusal.TOOL_LEVEL), ResourceRules.start(oak, wc, 40, rune, false, false))
        val both = setOf("item.bronze_axe", "item.rune_axe")
        assertEquals(Start.Ok(tool(wc, "rune_axe")), ResourceRules.start(oak, wc, 41, both, false, false))
        assertEquals(Start.Ok(tool(wc, "bronze_axe")), ResourceRules.start(oak, wc, 40, both, false, false))
        // The infernal axe is a TODO tool: never chosen, and alone it counts as no axe.
        assertEquals(Start.Ok(tool(wc, "bronze_axe")), ResourceRules.start(oak, wc, 99, setOf("item.bronze_axe", "item.infernal_axe"), false, false))
        assertEquals(Start.Refused(Refusal.NO_TOOL), ResourceRules.start(oak, wc, 99, setOf("item.infernal_axe"), false, false))
        assertEquals(Start.Refused(Refusal.INVENTORY_FULL), ResourceRules.start(oak, wc, 99, rune, inventoryFull = true, rewardStacksIntoHeld = false))
        assertIs<Start.Ok>(ResourceRules.start(oak, wc, 99, rune, inventoryFull = true, rewardStacksIntoHeld = true))
    }

    @Test
    fun `roll intervals`() {
        assertEquals(4, ResourceRules.rollInterval(wc, tool(wc, "bronze_axe"), 0.0))
        assertEquals(8, ResourceRules.rollInterval(mining, tool(mining, "bronze_pickaxe"), 0.0))
        assertEquals(3, ResourceRules.rollInterval(mining, tool(mining, "rune_pickaxe"), 0.0))
        assertEquals(2, ResourceRules.rollInterval(mining, tool(mining, "dragon_pickaxe"), 0.1))
        assertEquals(3, ResourceRules.rollInterval(mining, tool(mining, "dragon_pickaxe"), 0.5))
        assertEquals(2, ResourceRules.rollInterval(mining, tool(mining, "crystal_pickaxe"), 0.2))
        assertEquals(3, ResourceRules.rollInterval(mining, tool(mining, "crystal_pickaxe"), 0.3))
    }

    @Test
    fun `the gem pre-roll comes first`() {
        val copper = nodes.getValue("copper")
        val pick = tool(mining, "bronze_pickaxe")
        fun roll(pre: Int, success: Int) = ResourceRules.roll(copper, mining, pick, 1, Rolls(pre, success, 0.99, 0.99), false)
        assertEquals(Outcome("item.uncut_diamond", 0.0, depletes = false, gem = true), roll(127, 0))
        // 0 is the table's Nothing row: no gem, the success roll decides.
        assertEquals(Outcome("item.copper_ore", 17.5, depletes = true), roll(0, 0))
        assertEquals(Outcome("item.copper_ore", 17.5, depletes = true), roll(128, 0))
        assertEquals(Outcome.NOTHING, roll(128, 101))
        assertEquals("item.uncut_sapphire", roll(70, 0).item)
    }

    @Test
    fun `depletion`() {
        val oak = nodes.getValue("oak")
        val axe = tool(wc, "bronze_axe")
        fun roll(expired: Boolean) = ResourceRules.roll(oak, wc, axe, 99, Rolls(0, 0, 0.0, 0.99), expired)
        assertFalse(roll(false).depletes)
        assertTrue(roll(true).depletes)
        assertEquals("item.oak_logs", roll(true).item, "the TODO nest tertiary is never applied, even on a 0 roll")
        assertTrue(ResourceRules.roll(nodes.getValue("tree"), wc, axe, 99, Rolls(0, 0, 0.0, 0.99), false).depletes)
        assertTrue(ResourceRules.deplete(Depletion.Chance(1, 16), false, 0.05))
        assertFalse(ResourceRules.deplete(Depletion.Chance(1, 16), false, 0.5))
    }

    @Test
    fun `a loaded tertiary replaces the reward`() {
        val fixture = Tertiary(1, 256, "item.uncut_sapphire", replacesReward = true, todo = null)
        val oak = nodes.getValue("oak").copy(tertiary = listOf(fixture))
        val axe = tool(wc, "bronze_axe")
        assertEquals("item.uncut_sapphire", ResourceRules.roll(oak, wc, axe, 99, Rolls(0, 0, 0.001, 0.0), false).item)
        assertEquals("item.oak_logs", ResourceRules.roll(oak, wc, axe, 99, Rolls(0, 0, 0.5, 0.0), false).item)
    }

    @Test
    fun `respawn ticks`() {
        assertEquals(14, ResourceRules.respawnTicks(nodes.getValue("oak").respawn!!, 0.5))
        assertEquals(59, ResourceRules.respawnTicks(nodes.getValue("tree").respawn!!, 0.0))
        assertEquals(98, ResourceRules.respawnTicks(nodes.getValue("tree").respawn!!, 0.999))
    }

    @Test
    fun `success uses the tool tier for trees and the level line for rocks`() {
        val tree = nodes.getValue("tree")
        assertEquals(ResourceRules.chance256(224, 700, 50), ResourceRules.successChance(tree, tool(wc, "rune_axe"), 50))
        val iron = nodes.getValue("iron")
        assertEquals(
            ResourceRules.successChance(iron, tool(mining, "bronze_pickaxe"), 40),
            ResourceRules.successChance(iron, tool(mining, "crystal_pickaxe"), 40),
            "the pickaxe does not change the success rate",
        )
    }
}
