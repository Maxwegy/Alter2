package org.alter.plugins.content.combat.autocast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutocastRulesTests {
    private val standard = SpellGroup("standard-elemental", "normal", null, mapOf("WIND_STRIKE" to 1, "FIRE_WAVE" to 16, "FIRE_SURGE" to 51), null, null)
    private val ancients = SpellGroup("ancients", "ancients", "item.ancient_staff", mapOf("SMOKE_RUSH" to 31, "ICE_BARRAGE" to 46), null, null)
    private val staff = listOf(standard)
    private val ancientStaff = listOf(standard, ancients)
    private val sword = emptyList<SpellGroup>()

    @Test
    fun `a selection the new weapon can cast is kept`() {
        assertEquals(16, AutocastRules.onWeaponChange(16, ancientStaff, emptyMap(), equipping = true, pvpLocked = false))
        assertEquals(46, AutocastRules.onWeaponChange(46, ancientStaff, mapOf("ancients" to 31), equipping = true, pvpLocked = false))
    }

    @Test
    fun `otherwise the group memory is restored, else it is cleared`() {
        // Ice barrage on an ancient staff, then a plain staff: the standard group's remembered fire wave comes back.
        assertEquals(16, AutocastRules.onWeaponChange(46, staff, mapOf("standard-elemental" to 16, "ancients" to 46), equipping = true, pvpLocked = false))
        // Back to the ancient staff with nothing selected: the preferred group's memory wins.
        val memory = mapOf("standard-elemental" to 16, "ancients" to 46)
        assertEquals(46, AutocastRules.onWeaponChange(0, ancientStaff, memory, equipping = true, pvpLocked = false, preferredGroup = "ancients"))
        assertEquals(16, AutocastRules.onWeaponChange(0, ancientStaff, memory, equipping = true, pvpLocked = false, preferredGroup = "standard-elemental"))
        // No memory for the group, or a weapon that can't autocast: cleared.
        assertEquals(0, AutocastRules.onWeaponChange(46, staff, mapOf("ancients" to 46), equipping = true, pvpLocked = false))
        assertEquals(0, AutocastRules.onWeaponChange(16, sword, memory, equipping = true, pvpLocked = false))
        // A remembered id that is no longer in its group is ignored.
        assertEquals(0, AutocastRules.onWeaponChange(0, staff, mapOf("standard-elemental" to 46), equipping = true, pvpLocked = false))
    }

    @Test
    fun `equipping a staff during the PvP swap lock clears it`() {
        assertEquals(0, AutocastRules.onWeaponChange(16, staff, mapOf("standard-elemental" to 16), equipping = true, pvpLocked = true))
        // Unequipping (or logging in) is not "equipping a staff".
        assertEquals(16, AutocastRules.onWeaponChange(16, staff, emptyMap(), equipping = false, pvpLocked = true))
        // Equipping a weapon that can't autocast clears it anyway.
        assertEquals(0, AutocastRules.onWeaponChange(16, sword, emptyMap(), equipping = true, pvpLocked = true))
    }

    @Test
    fun `selecting a spell records it for its group`() {
        assertEquals(mapOf("standard-elemental" to 16, "ancients" to 31), AutocastRules.onSelect(31, ancientStaff, mapOf("standard-elemental" to 16)))
        assertNull(AutocastRules.onSelect(31, staff, emptyMap()))
        assertNull(AutocastRules.onSelect(0, staff, emptyMap()))
        assertTrue(AutocastRules.allowed(staff, 51))
        assertTrue(!AutocastRules.allowed(staff, 0))
    }

    @Test
    fun `the memory codec round-trips and drops unknown entries`() {
        val memory = mapOf("ancients" to 46, "standard-elemental" to 16)
        val text = AutocastRules.encode(memory, ancientStaff)
        assertEquals("standard-elemental=FIRE_WAVE;ancients=ICE_BARRAGE", text)
        assertEquals(memory, AutocastRules.decode(text, ancientStaff))
        assertEquals(mapOf("standard-elemental" to 16), AutocastRules.decode("standard-elemental=FIRE_WAVE;lunar=X;ancients=FIRE_WAVE;junk", ancientStaff))
        assertEquals(emptyMap(), AutocastRules.decode(null, ancientStaff))
        assertEquals("", AutocastRules.encode(emptyMap(), ancientStaff))
    }

    @Test
    fun `the memory is saved and survives death`() {
        assertEquals("autocast_memory", Autocast.MEMORY_ATTR.persistenceKey)
        assertTrue(!Autocast.MEMORY_ATTR.resetOnDeath)
    }
}
