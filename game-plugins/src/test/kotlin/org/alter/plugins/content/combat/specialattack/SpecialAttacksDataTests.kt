package org.alter.plugins.content.combat.specialattack

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `data/cfg/combat/special_attacks.json` is hand-maintained; these checks keep it honest and resolvable without a cache. */
class SpecialAttacksDataTests {
    private val table = SpecialAttackDefs.load(Paths.get("../data/cfg/combat/special_attacks.json"))

    /** Names in the committed RSCM table (`name:id` lines). */
    private val rscmItems: Set<String> = Files.readAllLines(Paths.get("../data/cfg/rscm/item.rscm"))
        .filter { ':' in it }.map { "item." + it.substringBeforeLast(':').trim() }.toSet()

    private val allItems = table.specials.flatMap { it.items }

    @Test
    fun `every item is an RSCM name, defined once`() {
        assertEquals(emptyList(), allItems.filter { it !in rscmItems })
        assertEquals(allItems.size, allItems.toSet().size, "an item appears in two entries")
    }

    @Test
    fun `every entry cites the OSRS wiki`() {
        val uncited = table.specials.filter { it.source?.startsWith("https://oldschool.runescape.wiki/") != true }.map { it.items.first() }
        assertEquals(emptyList(), uncited)
    }

    @Test
    fun `loaded entries are well formed`() {
        table.specials.forEach { assertTrue(it.energy in 5..100, "${it.items.first()}: energy ${it.energy}") }
        table.loaded.forEach { def ->
            assertTrue(def.animation != null, "${def.items.first()} has no animation")
            assertTrue(def.hits.isNotEmpty() || def.effects.isNotEmpty(), "${def.items.first()} does nothing")
        }
    }

    @Test
    fun `the migrated weapons and the new batch are loaded`() {
        val expected = listOf(
            "dragon_dagger", "dragon_dagger_p", "dragon_dagger_p+", "dragon_dagger_p++",
            "abyssal_dagger", "abyssal_dagger_p", "abyssal_dagger_p+", "abyssal_dagger_p++",
            "abyssal_bludgeon", "armadyl_godsword", "armadyl_godsword_or", "dragon_pickaxe",
            "bandos_godsword", "saradomin_godsword", "zamorak_godsword", "dragon_warhammer", "dragon_mace", "dragon_longsword",
            "dragon_halberd", "abyssal_whip", "arclight", "saradomin_sword", "granite_maul", "dragon_axe", "excalibur",
        ).map { "item.$it" }
        assertEquals(emptyList(), expected.filter { it !in table.byItem })
        assertEquals(25, table.byItem.getValue("item.abyssal_dagger").energy)
        assertEquals("Smashing!", table.byItem.getValue("item.dragon_pickaxe").forceChat)
    }

    @Test
    fun `the TODO weapons are present but skipped`() {
        val todo = listOf(
            "dragon_claws", "dragon_scimitar", "dragon_battleaxe", "dragon_sword", "dragon_2h_sword", "dragon_spear", "magic_shortbow", "dark_bow",
        ).map { "item.$it" }
        assertEquals(emptyList(), todo.filter { it !in allItems })
        assertEquals(emptyList(), todo.filter { it in table.byItem })
        assertEquals(8, table.skipped.size)
    }

    @Test
    fun `energy and messages blocks`() {
        assertNull(table.messages.insufficientEnergy)
        assertEquals(EnergySettings(100, 10, 50, true), table.energy)
    }

    @Test
    fun `the parser rejects a bad entry`() {
        fun file(entry: String) = """{"schemaVersion":1,"energy":{"max":100,"regenPercent":10,"regenIntervalTicks":50},"messages":{},"specials":[$entry]}"""
        assertFailsWith<IllegalArgumentException> { SpecialAttackDefs.parse(file("""{"items":["item.x"],"energy":101,"animation":1,"combat":"melee","hits":[{}]}""")) }
        assertFailsWith<IllegalArgumentException> { SpecialAttackDefs.parse(file("""{"items":["item.x"],"energy":50,"animation":1}""")) }
        assertFailsWith<IllegalArgumentException> { SpecialAttackDefs.parse(file("""{"items":["item.x"],"energy":50,"combat":"melee","hits":[{}]}""")) }
        assertFailsWith<IllegalArgumentException> { SpecialAttackDefs.parse(file("""{"items":["item.x"],"energy":50,"trigger":"bar-click","animation":1,"effects":[{"boostSelf":{"skill":"sailing","plus":1}}]}""")) }
        // A TODO entry needs neither hits nor an animation.
        assertEquals(1, SpecialAttackDefs.parse(file("""{"items":["item.x"],"energy":50,"todo":"later"}""")).skipped.size)
    }
}
