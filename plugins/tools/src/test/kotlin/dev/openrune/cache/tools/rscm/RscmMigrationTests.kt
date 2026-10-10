package dev.openrune.cache.tools.rscm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RscmMigrationTests {
    // 228-era table: display names collide, so Namer added `_<id>`; 34 was unnamed.
    private val previous = linkedMapOf("shark" to 385, "tree" to 1276, "tree_1277" to 1277, "coins" to 617, "null_34" to 34, "old_thing" to 90, "gone" to 91)
    // 241 gameval names (canonical): unique, id-free; `coins` names a different id than our old `coins`.
    private val gameval = mapOf(385 to "shark", 1276 to "tree", 1277 to "tree_oak", 617 to "coins_old", 995 to "coins", 34 to "cert_candle", 90 to "yama", 91 to "mystery", 200 to "old_thing_v2")
    // 241 decoded display names (sanitized base).
    private val newDisplay = mapOf(385 to "shark", 1276 to "tree", 1277 to "tree", 617 to "coins", 995 to "coins", 34 to "null", 90 to "yama", 91 to "null", 200 to "old_thing")
    private val oldDisplay = mapOf(385 to "shark", 1276 to "tree", 1277 to "tree", 617 to "coins", 34 to "null", 90 to "old_thing", 91 to "gone")

    private fun run(referenced: Set<String> = emptySet(), overrides: Map<String, RscmOverride> = emptyMap()) =
        RscmMigration.migrate(RscmTable.ITEM, previous, gameval, newDisplay, oldDisplay, referenced, overrides)

    private fun Migration.outcome(name: String) = entries.first { it.oldName == name }.outcome

    @Test
    fun `each committed name gets exactly one outcome`() {
        val m = run()
        assertEquals(Outcome.IDENTICAL, m.outcome("shark"))
        assertEquals(Outcome.IDENTICAL, m.outcome("tree"))
        assertEquals(Outcome.SAME_ID, m.outcome("tree_1277"))
        assertEquals(Outcome.CLASH, m.outcome("coins"))
        assertEquals(Outcome.DROPPED_WAS_NULL, m.outcome("null_34"))
        assertEquals(Outcome.REMAPPED, m.outcome("old_thing")) // 90 is Yama now; 200 holds the old thing
        assertEquals(Outcome.UNRESOLVED, m.outcome("gone")) // 91 unnamed, nothing else is called `gone`
        assertEquals(previous.size, m.entries.size)
        assertEquals(listOf("tree_1277" to 1277, "old_thing" to 200), m.aliases)
        assertEquals(gameval.toSortedMap(), m.canonical)
    }

    @Test
    fun `a referenced null name is kept as an alias while its id stays unnamed`() {
        val m = run(referenced = setOf("null_34"))
        assertEquals(Outcome.SAME_ID, m.outcome("null_34"))
        assertTrue(m.aliases.contains("null_34" to 34))
    }

    @Test
    fun `only referenced clashes and unresolved names block`() {
        assertTrue(run().blocking.isEmpty())
        assertEquals(listOf("coins", "gone"), run(referenced = setOf("coins", "gone", "shark")).blocking.map { it.oldName })
    }

    @Test
    fun `overrides decide clashes and unresolved names`() {
        val m = run(
            referenced = setOf("coins", "gone"),
            overrides = mapOf(
                "item.coins" to RscmOverride("rename-alias", name = "coins__228"),
                "item.gone" to RscmOverride("map-to", id = 91),
                "item.tree_1277" to RscmOverride("drop"),
            ),
        )
        assertTrue(m.blocking.isEmpty())
        assertEquals(Outcome.OVERRIDDEN, m.outcome("coins"))
        assertEquals(listOf("coins__228" to 617, "old_thing" to 200, "gone" to 91), m.aliases)
        assertNull(m.aliases.firstOrNull { it.first == "tree_1277" })
    }

    @Test
    fun `an alias can never shadow a canonical name or another alias`() {
        val m = run(overrides = mapOf("item.gone" to RscmOverride("rename-alias", name = "coins"), "item.tree_1277" to RscmOverride("rename-alias", name = "old_thing")))
        assertEquals(Outcome.UNRESOLVED, m.outcome("gone")) // `coins` is canonical
        // `old_thing` is emitted once: the first claimant (tree_1277's override) keeps it, the natural `old_thing` alias is refused
        assertEquals(1, m.aliases.count { it.first == "old_thing" })
        assertEquals(Outcome.UNRESOLVED, m.outcome("old_thing"))
    }

    @Test
    fun `written lines are canonical then aliases, sorted and valid`() {
        val m = run()
        val lines = RscmGenerate(java.nio.file.Paths.get("."), 241, java.nio.file.Paths.get("."), null, 228, null, emptyList(), java.nio.file.Paths.get(".")).rscmLines(m)
        assertEquals("cert_candle:34", lines.first())
        assertEquals(listOf("old_thing:200", "tree_1277:1277"), lines.takeLast(2))
        assertEquals(lines.size, lines.map { it.substringBeforeLast(':') }.toSet().size)
        lines.forEach { assertTrue(it, Regex("^[a-z0-9_]+:\\d+$").matches(it)) }
    }
}
