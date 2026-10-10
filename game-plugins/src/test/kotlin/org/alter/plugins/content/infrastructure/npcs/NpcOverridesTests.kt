package org.alter.plugins.content.infrastructure.npcs

import org.alter.game.model.combat.NpcCombatDef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NpcOverridesTests {
    /** Stands in for a hand-written `setCombatDef` (arbitrary fixture values): animations, sounds, bonuses, species, immunities. */
    private val handWritten = NpcCombatDef.DEFAULT.copy(
        hitpoints = 8,
        attack = 3,
        strength = 4,
        defence = 5,
        magic = 6,
        ranged = 7,
        attackSpeed = 6,
        respawnDelay = 45,
        attackAnimation = 9001,
        blockAnimation = 9002,
        deathAnimation = listOf(9003),
        defaultAttackSound = 9004,
        defaultBlockSound = 9005,
        defaultDeathSound = 9006,
        bonuses = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14),
        species = setOf("SPECIES_MARKER"),
        immunePoison = true,
        immuneVenom = true,
        slayerReq = 50,
        slayerXp = 12.5,
    )

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
    }

    @Test
    fun `set fields change and unset fields keep the hand-written value`() {
        val result = NpcOverride(hitpoints = 20, attackSpeed = 4).applyTo(handWritten)
        assertEquals(handWritten.copy(hitpoints = 20, attackSpeed = 4), result)
        assertEquals(handWritten.attackAnimation, result.attackAnimation)
        assertEquals(handWritten.deathAnimation, result.deathAnimation)
        assertEquals(handWritten.defaultAttackSound, result.defaultAttackSound)
        assertEquals(handWritten.bonuses, result.bonuses)
        assertEquals(handWritten.species, result.species)
        assertTrue(result.immunePoison && result.immuneVenom)
    }

    @Test
    fun `every stat field maps onto the def`() {
        val o = NpcOverride(hitpoints = 1, attack = 2, strength = 3, defence = 4, magic = 5, ranged = 6, attackSpeed = 7, respawnTicks = 8)
        val result = o.applyTo(handWritten)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), with(result) { listOf(hitpoints, attack, strength, defence, magic, ranged, attackSpeed, respawnDelay) })
        assertEquals(listOf("hitpoints", "attack", "strength", "defence", "magic", "ranged", "attackSpeed", "respawnTicks"), o.fields)
    }

    @Test
    fun `an override that sets nothing returns the same object`() {
        assertSame(handWritten, NpcOverride(npc = "npc.cow").applyTo(handWritten))
        assertTrue(NpcOverride(npcs = listOf("npc.cow")).fields.isEmpty())
    }

    @Test
    fun `aggressive false clears aggression and true gives a passive npc the defaults`() {
        val aggressive = handWritten.copy(aggressiveRadius = 8, aggroTargetDelay = 3, aggressiveTimer = 500)
        val off = NpcOverride(aggressive = false).applyTo(aggressive)
        assertEquals(Triple(0, 0, 0), Triple(off.aggressiveRadius, off.aggroTargetDelay, off.aggressiveTimer))
        val kept = NpcOverride(aggressive = true).applyTo(aggressive)
        assertEquals(Triple(8, 3, 500), Triple(kept.aggressiveRadius, kept.aggroTargetDelay, kept.aggressiveTimer))
        val on = NpcOverride(aggressive = true).applyTo(handWritten)
        assertEquals(
            Triple(NpcOverride.DEFAULT_AGGRO_RADIUS, NpcOverride.DEFAULT_AGGRO_SEARCH_DELAY, NpcOverride.DEFAULT_AGGRO_TIMER),
            Triple(on.aggressiveRadius, on.aggroTargetDelay, on.aggressiveTimer),
        )
    }

    @Test
    fun `overlay touches only hand-written ids that have an override`() {
        val wiki = NpcCombatDef.DEFAULT.copy(hitpoints = 99)
        val other = handWritten.copy(hitpoints = 50)
        val defs = mutableMapOf(1 to handWritten, 2 to wiki, 3 to other)
        val originals = HashMap<Int, NpcCombatDef>()
        val overrides = mapOf(1 to NpcOverride(hitpoints = 20), 2 to NpcOverride(hitpoints = 1), 4 to NpcOverride(hitpoints = 1))

        val overlaid = NpcOverrides.overlay(defs, overrides, owned = setOf(2), originals = originals)

        assertEquals(setOf(1), overlaid)
        assertEquals(handWritten.copy(hitpoints = 20), defs[1])
        assertSame(wiki, defs[2], "wiki-owned defs get their override when they are built")
        assertSame(other, defs[3], "no override: the same object")
        assertNull(defs[4], "an override without any def creates none")
        assertSame(handWritten, originals[1])
    }

    @Test
    fun `deleting the override and reloading restores the original`() {
        val defs = mutableMapOf(1 to handWritten)
        val originals = HashMap<Int, NpcCombatDef>()
        val overlaid = NpcOverrides.overlay(defs, mapOf(1 to NpcOverride(hitpoints = 20)), emptySet(), originals)

        NpcOverrides.restore(defs, overlaid, originals)
        val again = NpcOverrides.overlay(defs, emptyMap(), emptySet(), originals)

        assertTrue(again.isEmpty())
        assertSame(handWritten, defs[1])
    }

    @Test
    fun `two reloads do not compound`() {
        val defs = mutableMapOf(1 to handWritten)
        val originals = HashMap<Int, NpcCombatDef>()
        val overrides = mapOf(1 to NpcOverride(hitpoints = 20, aggressive = true))
        var overlaid = NpcOverrides.overlay(defs, overrides, emptySet(), originals)
        val first = defs[1]
        repeat(2) {
            NpcOverrides.restore(defs, overlaid, originals)
            overlaid = NpcOverrides.overlay(defs, overrides, emptySet(), originals)
        }
        assertEquals(first, defs[1])
        assertSame(handWritten, originals[1])
        // Even without a restore in between, the overlay is computed from the original.
        NpcOverrides.overlay(defs, mapOf(1 to NpcOverride(attack = 9)), emptySet(), originals)
        assertEquals(handWritten.copy(attack = 9), defs[1])
    }

    @Test
    fun `changed compares by value and counts removed defs`() {
        val before = mapOf(1 to handWritten, 2 to handWritten, 3 to handWritten)
        val after = mapOf(1 to handWritten.copy(), 2 to handWritten.copy(hitpoints = 1))
        assertEquals(setOf(2, 3), NpcOverrides.changed(before, after, setOf(1, 2, 3)))
    }

    @Test
    fun `origin names the def source and the override fields`() {
        val o = NpcOverride(hitpoints = 20, aggressive = false)
        assertEquals("none (engine default)", NpcOverrides.origin(hasDef = false, wikiOwned = false, override = null))
        assertEquals("hand-written plugin", NpcOverrides.origin(hasDef = true, wikiOwned = false, override = null))
        assertEquals("hand-written plugin + override (hitpoints, aggressive)", NpcOverrides.origin(true, false, o))
        assertEquals("wiki snapshot + cache", NpcOverrides.origin(true, true, null))
        assertEquals("wiki snapshot + cache + override (hitpoints, aggressive)", NpcOverrides.origin(true, true, o))
    }

    @Test
    fun `load reads yml and yaml files in name order, later files win, bad files are reported`() {
        val dir = Files.createTempDirectory("npc-overrides").also(tempDirs::add)
        dir.resolve("a.yml").writeText("npcs: [npc.cow, npc.king_black_dragon]\nhitpoints: 20\n")
        dir.resolve("b.yaml").writeText("npc: npc.cow\nattack: 5\nsomeFutureField: 1\n")
        dir.resolve("c.yml").writeText("npc: npc.unknown\nhitpoints: 1\n")
        dir.resolve("d.yml").writeText("hitpoints: [not, a, number]\n")
        dir.resolve("README.md").writeText("not an override")
        val ids = mapOf("npc.cow" to 1, "npc.king_black_dragon" to 2)
        val errors = mutableListOf<String>()

        val loaded = NpcOverrides.load(dir, { ids[it] ?: error("unknown $it") }) { file, _ -> errors += file.fileName.toString() }

        assertEquals(setOf(1, 2), loaded.keys)
        assertEquals(NpcOverride(npc = "npc.cow", attack = 5), loaded[1])
        assertEquals(20, loaded[2]?.hitpoints)
        assertEquals(listOf("c.yml", "d.yml"), errors)
    }

    @Test
    fun `load of a missing directory is empty`() {
        val dir = Files.createTempDirectory("npc-overrides").also(tempDirs::add)
        assertTrue(NpcOverrides.load(dir.resolve("missing"), { 0 }) { _, _ -> error("no files") }.isEmpty())
    }
}
