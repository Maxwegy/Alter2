package org.alter.data.snapshot

import org.alter.data.FakeCacheView
import org.alter.data.items.ItemStatsMapper
import org.alter.data.npcs.NpcStatsMapper
import org.alter.data.report.ReportBuilder
import org.alter.data.wiki.BucketRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnapshotRoundTripTests {
    private val cache = FakeCacheView(npcIds = setOf(415, 416), items = mapOf(4151 to "Abyssal whip"))

    /** Live infobox_monster row for Abyssal demon#Standard (2026-10-09), trimmed to the fields the mapper reads. */
    private val demon: BucketRow = mapOf(
        "page_name" to "Abyssal demon", "page_name_sub" to "Abyssal demon#Standard", "id" to listOf("415", "416"),
        "version_anchor" to "Standard", "combat_level" to 124, "hitpoints" to 150, "attack_speed" to 4, "respawn_time" to 12,
        "size" to 1, "attack_level" to 97, "strength_level" to 67, "defence_level" to 135, "ranged_level" to 1, "magic_level" to 1,
        "stab_defence_bonus" to 20, "slash_defence_bonus" to 20, "crush_defence_bonus" to 20, "magic_defence_bonus" to 0,
        "range_defence_bonus" to 20, "standard_range_defence_bonus" to 20, "light_range_defence_bonus" to 20,
        "heavy_range_defence_bonus" to 20, "max_hit" to listOf("8"), "attack_style" to listOf("Stab"), "is_aggressive" to "No",
        "poisonous" to "No", "poison_resistance" to "0", "venom_resistance" to "0", "cannon_immune" to "Not immune",
        "thrall_immune" to "Not immune", "attribute" to listOf("demon"), "slayer_level" to 85, "slayer_experience" to 150,
        "slayer_category" to listOf("Abyssal Demons"),
    )

    /** Live infobox_bonuses ⋈ infobox_item row for the Abyssal whip. */
    private val whip: BucketRow = mapOf(
        "page_name" to "Abyssal whip", "page_name_sub" to "Abyssal whip", "infobox_item.item_id" to listOf("4151"),
        "equipment_slot" to "weapon", "weapon_attack_speed" to 4, "weapon_attack_range" to "1", "combat_style" to "Whip",
        "slash_attack_bonus" to 82, "strength_bonus" to 82,
    )

    private fun snapshot(): Snapshot {
        val report = ReportBuilder("test")
        return Snapshot(ItemStatsMapper(cache).map(listOf(whip), report), NpcStatsMapper(cache).map(listOf(demon), report), emptyList())
    }

    @Test
    fun `npc rows map to normalized stats`() {
        val entry = snapshot().npcs.single().versions.single()
        assertEquals(listOf(415, 416), entry.ids)
        assertEquals(NpcLevels(attack = 97, strength = 67, defence = 135, ranged = 1, magic = 1), entry.levels)
        assertEquals(150, entry.hitpoints)
        assertEquals(20, entry.bonuses!!.rangedDefence)
        assertFalse(entry.immunities.poison)
        assertEquals(listOf("demon"), entry.attributes)
        assertEquals(85, entry.slayer!!.level)
    }

    @Test
    fun `item rows map to bonuses and wiki-only fields`() {
        val whip = snapshot().items.single()
        assertEquals(4151, whip.id)
        assertEquals(82, whip.bonuses.slashAttack)
        assertEquals(82, whip.bonuses.meleeStrength)
        assertEquals(1, whip.attackRange)
        assertEquals("Whip", whip.combatStyle)
    }

    @Test
    fun `writing twice is a no-op and the repository reads it back`() {
        val dir = Files.createTempDirectory("snapshot")
        val writer = SnapshotWriter(dir)
        val first = writer.write(snapshot(), "test", 228, listOf("test"), mapOf("infobox_monster" to 1))
        assertTrue(first.changed)
        val bytes = Files.readAllBytes(dir.resolve("npcs/abyssal_demon.json"))
        val second = writer.write(snapshot(), "test", 228, listOf("test"), mapOf("infobox_monster" to 1))
        assertFalse(second.changed)
        assertTrue(bytes.contentEquals(Files.readAllBytes(dir.resolve("npcs/abyssal_demon.json"))))
        assertFalse(String(bytes).contains("\r"))

        val loaded = assertIs<SnapshotRepository.LoadResult.Loaded>(SnapshotRepository.load(dir)).repository
        assertEquals(150, loaded.npcStats(416)?.hitpoints)
        assertEquals(1, loaded.itemStats(4151)?.attackRange)
        assertEquals(228, loaded.cacheRevision)
    }

    @Test
    fun `stale page files are removed`() {
        val dir = Files.createTempDirectory("snapshot")
        val writer = SnapshotWriter(dir)
        writer.write(snapshot(), "test", 228, emptyList(), emptyMap())
        val result = writer.write(snapshot().copy(npcs = emptyList()), "test", 228, emptyList(), emptyMap())
        assertEquals(listOf("npcs/abyssal_demon.json"), result.removed)
    }

    @Test
    fun `schema mismatch is detected`() {
        val dir = Files.createTempDirectory("snapshot")
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":99,"generator":"x","cacheRevision":1,"sources":[],"rowCounts":{},"files":{}}""")
        assertEquals(SnapshotRepository.LoadResult.SchemaMismatch(99, SNAPSHOT_SCHEMA_VERSION), SnapshotRepository.load(dir))
    }

    @Test
    fun `colliding slugs get a stable suffix`() {
        val slugs = SnapshotWriter.slugs(listOf("Man (A)", "Man A", "Goblin"))
        assertEquals("goblin", slugs["Goblin"])
        assertTrue(slugs.getValue("Man (A)") != slugs.getValue("Man A"))
        assertEquals(slugs, SnapshotWriter.slugs(listOf("Man (A)", "Man A", "Goblin")))
    }
}
