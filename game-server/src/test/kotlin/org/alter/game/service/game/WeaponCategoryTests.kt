package org.alter.game.service.game

import dev.openrune.cache.filestore.definition.data.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class WeaponCategoryTests {
    @Test
    fun `known categories map to their weapon type`() {
        assertEquals(WeaponCategory.WHIP.weaponType, WeaponCategory.get(ItemType(id = 4151), 150))
        assertEquals(WeaponCategory.BOW.weaponType, WeaponCategory.get(ItemType(id = 861), 64))
    }

    @Test
    fun `an unknown category is unarmed instead of aborting the item load`() {
        // Item 32712 in the 241 cache has category 2294, which this table does not list.
        assertEquals(WeaponCategory.UNARMED.weaponType, WeaponCategory.get(ItemType(id = 32712), 2294))
    }
}
