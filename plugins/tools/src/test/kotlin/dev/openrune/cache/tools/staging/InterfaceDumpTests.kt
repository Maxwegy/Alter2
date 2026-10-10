package dev.openrune.cache.tools.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceDumpTests {
    @Test
    fun `component names are id and name pairs after the interface name`() {
        val data = "orbs".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 0x24) + "specbutton".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 0) + "universe".toByteArray() + byteArrayOf(0) +
            byteArrayOf(-1, -1)
        assertEquals(mapOf(0 to "universe", 36 to "specbutton"), Gameval.componentNames(data))
        assertEquals(emptyMap<Int, String>(), Gameval.componentNames("orbs".toByteArray() + byteArrayOf(0, -1, -1)))
    }

    @Test
    fun `an if3 layer decodes its parent, click mask and ops`() {
        val data = byteArrayOf(
            -1, 0, // marker, type 0 (layer)
            0, 0, // content type
            0, 0, 0, -56, // x 0, y 200
            0, -106, 0, 26, // 150 x 26
            0, 0, 0, 0, // modes
            0, 38, // parent 38
            0, // not hidden
            0, 0, 0, 0, 0, // scroll size, no click-through
            0, 0, 2, // click mask: op1
            0, // name
            1, // one op
        ) + "Use Special Attack".toByteArray() + byteArrayOf(0)
        val c = If3Component.decode(593, 39, data)
        assertEquals("layer", c.typeName)
        assertEquals(38, c.parent)
        assertEquals(200, c.y)
        assertEquals(150, c.width)
        assertFalse(c.hidden)
        assertEquals(listOf("Use Special Attack"), c.ops)
        assertTrue(c.transmitsOp(1))
        assertFalse(c.transmitsOp(2))
        assertNull(c.error)
    }

    @Test
    fun `an unknown type is reported, not guessed`() {
        val data = byteArrayOf(-1, 12, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0)
        val c = If3Component.decode(1, 2, data)
        assertNull(c.clickMask)
        assertFalse(c.transmitsOp(1))
        assertEquals("type 12 not decoded", c.error)
    }
}
