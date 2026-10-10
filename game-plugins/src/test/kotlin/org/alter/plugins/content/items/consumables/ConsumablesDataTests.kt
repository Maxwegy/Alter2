package org.alter.plugins.content.items.consumables

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `data/cfg/consumables/consumables.json` is hand-maintained; these checks keep it honest and resolvable. */
class ConsumablesDataTests {
    private val table = Consumables.load(Paths.get("../data/cfg/consumables/consumables.json"))

    /** Names in the committed RSCM table (`name:id` lines), so the check needs no cache. */
    private val rscmItems: Set<String> = Files.readAllLines(Paths.get("../data/cfg/rscm/item.rscm"))
        .filter { ':' in it }.map { "item." + it.substringBeforeLast(':').trim() }.toSet()

    @Test
    fun `every item and replacement is an RSCM name`() {
        val missing = table.consumables.flatMap { listOfNotNull(it.item, it.replacement) }.filter { it !in rscmItems }
        assertEquals(emptyList(), missing)
        val gear = (table.prayerGear.worn + table.prayerGear.carried).filter { it !in rscmItems }
        assertEquals(emptyList(), gear)
    }

    @Test
    fun `no item is defined twice and every entry cites the OSRS wiki`() {
        assertEquals(table.consumables.size, table.byItem.size)
        val uncited = table.consumables.filter { it.source?.startsWith("https://oldschool.runescape.wiki/") != true }.map { it.item }
        assertEquals(emptyList(), uncited)
    }

    @Test
    fun `dose chains end at a vial and every heal or effect is well formed`() {
        table.consumables.filter { it.kind == Kind.POTION }.forEach { potion ->
            var current = potion
            var hops = 0
            while (current.replacement != "item.vial") {
                val next = table.byItem[current.replacement] ?: error("${current.item}: replacement ${current.replacement} is not a potion in the file")
                assertTrue(next.kind == Kind.POTION && next.effects == potion.effects && next.heal == potion.heal, "${potion.item} chain changes mechanics at ${next.item}")
                current = next
                assertTrue(++hops < 5, "${potion.item}: dose chain does not end")
            }
        }
        table.consumables.forEach { c ->
            assertTrue(c.heal != null || c.effects.isNotEmpty(), "${c.item} does nothing")
            (c.heal as? Heal.Brackets)?.let { b ->
                val covered = (1..99).all { level -> b.brackets.any { level in it.minLevel..it.maxLevel } }
                assertTrue(covered, "${c.item}: brackets leave a level uncovered")
            }
            assertTrue(c.delayTicks >= 1 && c.attackDelayTicks >= 0, "${c.item}: bad delays")
        }
    }

    @Test
    fun `every food from the old Food enum is still consumable`() {
        val old = listOf(
            "shrimps", "sardine", "herring", "mackerel", "trout", "cod", "pike", "salmon", "tuna", "rainbow_fish", "cave_eel", "lobster", "bass",
            "swordfish", "monkfish", "cooked_karambwan", "shark", "sea_turtle", "manta_ray", "dark_crab", "anglerfish", "cooked_chicken",
            "cooked_meat", "roast_beast_meat", "ugthanki_kebab__228", "bread", "onion",
        ).map { "item.$it" }
        assertEquals(emptyList(), old.filter { it !in table.byItem })
    }

    @Test
    fun `status effects are well formed and every expiry message is present`() {
        val statusPotions = table.consumables.filter { c -> c.effects.any { it is Effect.RunEnergy || it is Effect.Antipoison || it is Effect.Antifire } }
        assertTrue(statusPotions.size >= 44, "expected the 11 status potion chains, found ${statusPotions.size} entries")
        statusPotions.forEach { c ->
            assertEquals(Kind.POTION, c.kind, "${c.item} should be a potion")
            c.effects.forEach { e ->
                when (e) {
                    is Effect.RunEnergy -> assertTrue(e.restorePercent in 1..100 && (e.staminaTicks ?: 1) > 0, "${c.item}: bad runEnergy")
                    is Effect.Antipoison -> assertTrue(e.cure || e.immunityTicks > 0, "${c.item}: antipoison does nothing")
                    is Effect.Antifire -> assertTrue(e.ticks > 0, "${c.item}: antifire needs a duration")
                    else -> {}
                }
            }
        }
        listOf(table.messages.antifireWarning, table.messages.antifireExpired, table.messages.staminaExpired).forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `defaults follow the wiki delays`() {
        assertEquals(3, table.defaults.getValue(Kind.FOOD).attackDelayTicks)
        assertEquals(2, table.defaults.getValue(Kind.COMBO).attackDelayTicks)
        assertEquals(0, table.defaults.getValue(Kind.POTION).attackDelayTicks)
        assertEquals(3, table.defaults.getValue(Kind.POTION).delayTicks)
    }
}
