package org.alter.data.items

import org.alter.data.snapshot.ItemBonuses
import org.alter.data.snapshot.ItemEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemGapFillTests {
    private val whip = ItemEntry(id = 4151, page = "Abyssal whip", bonuses = ItemBonuses(slashAttack = 82, meleeStrength = 82), attackSpeed = 4, attackRange = 1)

    @Test
    fun `cache params win`() {
        // The cache defines every stat param: nothing to fill.
        assertTrue(ItemGapFill.fill(ItemGapFill.BONUS_PARAMS.toSet() + ItemGapFill.ATTACK_RATE_PARAM, emptySet(), whip).isEmpty)
    }

    @Test
    fun `an item with some stat params has a complete block in the cache`() {
        // Only slash attack (param 1) is stored: every other stat is 0 by omission, not unknown.
        val fill = ItemGapFill.fill(setOf(1), emptySet(), whip)
        assertTrue(fill.bonuses.isEmpty())
        assertEquals(4, fill.attackSpeed)
    }

    @Test
    fun `items without any stat params are filled from the wiki`() {
        val fill = ItemGapFill.fill(emptySet(), emptySet(), whip)
        assertEquals(mapOf(1 to 82, 10 to 82), fill.bonuses)
        assertEquals(4, fill.attackSpeed)
    }

    @Test
    fun `overrides are never overwritten`() {
        val fill = ItemGapFill.fill(emptySet(), setOf("bonus.1", "attackSpeed"), whip)
        assertEquals(mapOf(10 to 82), fill.bonuses)
        assertEquals(null, fill.attackSpeed)
    }

    @Test
    fun `fractional magic damage rounds to the server's integer percent`() {
        val staff = whip.copy(bonuses = ItemBonuses(magicDamage = 2.5))
        assertEquals(mapOf(12 to 3), ItemGapFill.fill(emptySet(), emptySet(), staff).bonuses)
    }
}
