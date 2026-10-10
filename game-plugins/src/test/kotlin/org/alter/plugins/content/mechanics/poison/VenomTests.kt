package org.alter.plugins.content.mechanics.poison

import kotlin.test.Test
import kotlin.test.assertEquals

/** Venom's rules against the wiki: https://oldschool.runescape.wiki/w/Venom and https://oldschool.runescape.wiki/w/Poison. */
class VenomTests {
    @Test
    fun `venom starts at 6 and rises by 2 per hit to a cap of 20, every 30 ticks`() {
        assertEquals(listOf(6, 8, 10, 12, 14, 16, 18, 20, 20), Venom.sequence(9))
        assertEquals(20, Venom.next(20))
        assertEquals(30, Venom.INTERVAL_TICKS)
    }

    @Test
    fun `an antipoison turns venom into poison at the venom's damage, an anti-venom cures it`() {
        assertEquals(Venom.CureOutcome.Downgraded(12), Venom.onPoisonCure(12, curesVenom = false))
        assertEquals(Venom.CureOutcome.Cured, Venom.onPoisonCure(12, curesVenom = true))
        assertEquals(Venom.CureOutcome.None, Venom.onPoisonCure(null, curesVenom = false))
    }

    @Test
    fun `venom replaces poison and poison never replaces venom`() {
        assertEquals(Venom.Status.Envenomed(6), Venom.onInflict(Venom.Status.Poisoned))
        assertEquals(Venom.Status.Envenomed(6), Venom.onInflict(Venom.Status.None))
        assertEquals(Venom.Status.Envenomed(14), Venom.onInflict(Venom.Status.Envenomed(14), venom = false))
        assertEquals(Venom.Status.Poisoned, Venom.onInflict(Venom.Status.None, venom = false))
    }
}
