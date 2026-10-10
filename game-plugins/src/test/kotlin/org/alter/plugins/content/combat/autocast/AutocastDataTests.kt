package org.alter.plugins.content.combat.autocast

import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `data/cfg/combat/autocast.json` is hand-maintained; these checks keep it honest and resolvable without a cache. */
class AutocastDataTests {
    private val table = AutocastDefs.load(Paths.get("../data/cfg/combat/autocast.json"))

    private val rscmItems: Set<String> = Files.readAllLines(Paths.get("../data/cfg/rscm/item.rscm"))
        .filter { ':' in it }.map { "item." + it.substringBeforeLast(':').trim() }.toSet()

    @Test
    fun `every item and menu key is an RSCM name`() {
        assertEquals(emptyList(), table.byItem.keys.filter { it !in rscmItems })
        assertEquals(emptyList(), table.groups.mapNotNull { it.menuKey }.filter { it !in rscmItems })
    }

    @Test
    fun `every group cites the OSRS wiki and its spells are CombatSpells`() {
        table.groups.forEach { g ->
            assertTrue(g.source?.startsWith("https://oldschool.runescape.wiki/") == true, "${g.name} has no wiki source")
            g.spells.forEach { (name, id) -> assertEquals(CombatSpell.valueOf(name).autoCastId, id) }
        }
    }

    @Test
    fun `the groups hold the sourced autocast ids`() {
        val byName = table.groups.associateBy { it.name }
        // The 241 menu lists: script 4512 (ids 1-16, 48-51) and script 4511 (ids 31-46); data/reports/autocast-241-spike.md.
        assertEquals((1..16).toSet() + (48..51).toSet(), byName.getValue("standard-elemental").spells.values.toSet())
        assertEquals((31..46).toSet(), byName.getValue("ancients").spells.values.toSet())
        assertEquals(listOf("standard-elemental", "ancients"), table.loadedGroups.map { it.name })
        assertTrue(!byName.getValue("arceuus").loaded)
    }

    @Test
    fun `staves autocast standard spells and the wiki list adds Ancients`() {
        assertEquals(listOf("standard-elemental"), table.groupsFor(null, 18).map { it.name })
        assertEquals(emptyList(), table.groupsFor(null, 13).map { it.name })
        listOf("ancient_staff", "kodai_wand", "ahrims_staff", "master_wand", "nightmare_staff", "blue_moon_spear").forEach {
            assertEquals(listOf("standard-elemental", "ancients"), table.groupsFor("item.$it", 0).map { g -> g.name }, it)
        }
        assertEquals(20, table.pvpSwapLockTicks)
    }

    @Test
    fun `the UI ids are sourced from the 241 cache`() {
        val ui = table.ui
        assertTrue(ui.bound)
        assertEquals("cache 241, data/reports/autocast-241-spike.md", ui.source)
        assertEquals(593, ui.combatInterface)
        assertEquals(28, ui.chooseSpellButton)
        assertEquals(23, ui.defensiveButton)
        assertEquals(SpellButtons(201, 1, 0, 58), ui.spellButtons)
        assertEquals(664, ui.menuVarp)
    }

    @Test
    fun `the parser rejects a bad file`() {
        fun file(groups: String, extra: String = "") = """{"schemaVersion":1,"pvpSwapLockTicks":20,"groups":[$groups]$extra}"""
        val ok = """{"name":"a","spells":["FIRE_WAVE"]}"""
        assertFailsWith<IllegalArgumentException> { AutocastDefs.parse(file("""{"name":"a","spells":["FIRE_WAVES"]}""")) }
        assertFailsWith<IllegalArgumentException> { AutocastDefs.parse(file("""{"name":"a","spells":[]}""")) }
        assertFailsWith<IllegalArgumentException> { AutocastDefs.parse(file(ok, ""","byWeaponType":{"18":["b"]}""")) }
        assertFailsWith<IllegalArgumentException> { AutocastDefs.parse(file("$ok,$ok")) }
        // A todo group may be empty, and a missing ui block leaves the UI unbound.
        val parsed = AutocastDefs.parse(file("""$ok,{"name":"b","spells":[],"todo":"later"}"""))
        assertEquals(listOf("a"), parsed.loadedGroups.map { it.name })
        assertTrue(!parsed.ui.bound)
        assertTrue(!AutocastDefs.parse(file(ok, ""","ui":{"chooseSpellButton":null,"todo":"unsourced"}""")).ui.bound)
    }
}
