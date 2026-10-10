package org.alter.plugins.content.skills.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeStateRegistryTests {
    private val registry = NodeStateRegistry<String>(heartbeatTicks = 10)
    private val a = NodeStateRegistry.key(1234, 10)

    @Test
    fun `the timer expires at 45 ticks, not 44`() {
        registry.touch(a, who = 1, cycle = 100)
        listOf(104, 108, 112, 116, 120, 124, 128, 132, 136, 140).forEach { registry.touch(a, 1, it) }
        assertFalse(registry.timerExpired(a, 45, 144))
        assertTrue(registry.timerExpired(a, 45, 145))
    }

    @Test
    fun `the timer resets once the heartbeats expire`() {
        registry.touch(a, 1, 0)
        // 30 ticks without a touch: the heartbeat expired, so this touch starts a new timer at 30.
        registry.touch(a, 1, 30)
        (34..74 step 4).forEach { registry.touch(a, 1, it) }
        assertFalse(registry.timerExpired(a, 45, 74))
        assertTrue(registry.timerExpired(a, 45, 75))
        // Nobody touching for longer than the heartbeat: the timer is gone.
        assertFalse(registry.timerExpired(a, 45, 200))
    }

    @Test
    fun `with two gatherers one releasing keeps the timer`() {
        registry.touch(a, 1, 0)
        registry.touch(a, 2, 2)
        registry.release(a, 1)
        (6..42 step 4).forEach { registry.touch(a, 2, it) }
        assertTrue(registry.timerExpired(a, 45, 45))
        registry.release(a, 2)
        assertFalse(registry.timerExpired(a, 45, 46))
    }

    @Test
    fun `due returns depleted nodes earliest first, once`() {
        val b = NodeStateRegistry.key(5678, 10)
        registry.touch(a, 1, 0)
        registry.deplete(a, cycle = 0, respawnTicks = 10, payload = "A")
        registry.deplete(b, cycle = 0, respawnTicks = 5, payload = "B")
        assertTrue(registry.isDepleted(a))
        assertFalse(registry.timerExpired(a, 1, 1), "depleting clears the timer")
        assertEquals(emptyList(), registry.due(4))
        assertEquals(listOf("B", "A"), registry.due(10))
        assertEquals(emptyList(), registry.due(100))
        assertFalse(registry.isDepleted(a))
        assertEquals(0, registry.depletedCount)
    }

    @Test
    fun `keys differ by tile and by type`() {
        assertNotEquals(NodeStateRegistry.key(1, 10), NodeStateRegistry.key(2, 10))
        assertNotEquals(NodeStateRegistry.key(1, 10), NodeStateRegistry.key(1, 11))
    }
}
