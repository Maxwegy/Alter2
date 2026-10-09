package org.alter.game.model.container

import org.alter.game.model.item.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Client-supplied slots must never index outside the container (a spell sends slot -1). */
class ItemContainerValidatedTests {
    private val container = ItemContainer(28, ContainerStackType.NORMAL).apply { this[3] = Item(4151) }

    @Test
    fun `negative slot from a spell is rejected`() = assertNull(container.validated(-1, 4151))

    @Test
    fun `slot past capacity is rejected`() = assertNull(container.validated(28, 4151))

    @Test
    fun `stale item id is rejected`() = assertNull(container.validated(3, 995))

    @Test
    fun `empty slot is rejected`() = assertNull(container.validated(4, 4151))

    @Test
    fun `matching slot and id returns the item`() = assertEquals(4151, container.validated(3, 4151)?.id)
}
