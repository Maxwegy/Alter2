package dev.openrune.cache.tools.rscm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Runs the generator on the staged 241 cache (with the staged 228 cache for old display names) when both exist. */
class RscmGenerateStagedTests {
    private val staged241: Path = Paths.get("../../data/cache-staging/241-2735")
    private val staged228: Path = Paths.get("../../data/cache-staging/228-2043")
    private val dataDir: Path = Paths.get("../../data")
    private val repo: Path = Paths.get("../..")

    private fun generate(out: Path) = RscmGenerate(
        cacheDir = staged241, build = 241,
        previousRscmDir = dataDir.resolve("cfg/rscm"), previousCacheDir = staged228, previousBuild = 228,
        overridesFile = dataDir.resolve("cfg/rscm-migrations/overrides.json"),
        referenceRoots = listOf(repo.resolve("game-plugins/src"), dataDir.resolve("cfg")),
        out = out,
    ).run()

    @Test
    fun `every name content references resolves to the same thing on 241, deterministically`() {
        assumeTrue("no staged 241 cache", Files.exists(staged241.resolve("main_file_cache.idx255")))
        assumeTrue("no staged 228 cache", Files.exists(staged228.resolve("main_file_cache.idx255")))
        val out1 = Files.createTempDirectory("rscm-out-1")
        val g = generate(out1)
        assertTrue(g.oldDisplayFromCache)
        assertEquals("blocking: " + g.blocking.joinToString { "${it.table}.${it.oldName}" }, 0, g.blocking.size)

        val references = RscmTables.references(listOf(repo.resolve("game-plugins/src"), dataDir.resolve("cfg")))
        var checked = 0
        for ((table, names) in references) {
            val overrides = g.migrations.getValue(table).entries.filter { it.outcome == Outcome.OVERRIDDEN }.map { it.oldName }.toSet()
            for (name in names) {
                if (name in overrides) continue // renamed aliases: content changes in the bump PR
                val id = g.resolve(table, name)
                assertNotNull("${table.rscm}.$name does not resolve", id)
                // A name that was never in the 228 table (e.g. halibut, added in 229) only has to resolve.
                val entry = g.migrations.getValue(table).entries.firstOrNull { it.oldName == name }
                val before = entry?.let { g.oldDisplay.getValue(table)[it.oldId] }
                if (before != null) {
                    assertEquals("${table.rscm}.$name ($id) changed meaning", before, g.newDisplay.getValue(table)[id!!])
                }
                checked++
            }
        }
        assertTrue("checked $checked references", checked > 900)
        // canonical blocks are the gameval tables one for one
        assertEquals(34646, g.migrations.getValue(RscmTable.ITEM).canonical.size)
        assertEquals(16631, g.migrations.getValue(RscmTable.NPC).canonical.size)
        assertEquals(62534, g.migrations.getValue(RscmTable.OBJECT).canonical.size)
        assertEquals(385, g.resolve(RscmTable.ITEM, "shark"))
        assertEquals(3105, g.resolve(RscmTable.NPC, "hans"))
        assertEquals(3108, g.resolve(RscmTable.NPC, "man_3108"))
        assertEquals(1276, g.resolve(RscmTable.OBJECT, "tree_1276"))

        // Byte-identical on a second run.
        val out2 = Files.createTempDirectory("rscm-out-2")
        generate(out2)
        for (sub in listOf("rscm/item.rscm", "rscm/npc.rscm", "rscm/object.rscm", "rscm-meta/item.tsv", "rscm-migrations/228-to-241.json", "migration.md")) {
            assertTrue(sub, Files.readAllBytes(out1.resolve(sub)).contentEquals(Files.readAllBytes(out2.resolve(sub))))
        }
    }
}
