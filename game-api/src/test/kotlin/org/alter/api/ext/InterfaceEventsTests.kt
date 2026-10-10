package org.alter.api.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceEventsTests {
    @Test
    fun `click ops move to the second mask, the rest stays in the first`() {
        val legacy = InterfaceEvent.PAUSE.flag or InterfaceEvent.ClickOp1.flag or InterfaceEvent.ClickOp3.flag or InterfaceEvent.ClickOp10.flag
        val (events1, events2) = legacyEventsToV2(legacy)
        assertEquals(InterfaceEvent.PAUSE.flag, events1)
        assertEquals((1 shl 0) or (1 shl 2) or (1 shl 9), events2)
    }

    @Test
    fun `a mask without click ops leaves the second mask empty`() {
        val (events1, events2) = legacyEventsToV2(InterfaceEvent.PAUSE.flag)
        assertEquals(InterfaceEvent.PAUSE.flag, events1)
        assertEquals(0, events2)
    }
}
