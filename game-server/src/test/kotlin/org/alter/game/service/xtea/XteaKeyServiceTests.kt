package org.alter.game.service.xtea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XteaKeyServiceTests {
    @Test
    fun `keys are required up to build 236 and not from 237 on`() {
        assertTrue(XteaKeyService.requiresKeys(228))
        assertTrue(XteaKeyService.requiresKeys(236))
        assertFalse(XteaKeyService.requiresKeys(237))
        assertFalse(XteaKeyService.requiresKeys(241))
    }
}
