package org.alter.data

import org.alter.data.cache.CacheView

/** An in-memory [CacheView] for tests: items by id and name, noted links, NPC ids. */
class FakeCacheView(
    override val revision: Int = 228,
    private val npcIds: Set<Int> = emptySet(),
    /** id to name, canonical items only. */
    private val items: Map<Int, String> = emptyMap(),
    /** unnoted id to noted id. */
    private val noted: Map<Int, Int> = emptyMap(),
) : CacheView {
    override fun hasNpc(id: Int) = id in npcIds

    override fun hasItem(id: Int) = id in items || id in noted.values

    override fun itemName(id: Int) = items[id]

    override fun isNotedOrPlaceholder(id: Int) = id in noted.values

    override fun notedId(id: Int) = noted[id]

    override fun itemIdsByName(name: String) = items.filterValues { it.equals(name, ignoreCase = true) }.keys.sorted()

    override fun rscmName(table: String, id: Int) = when (table) {
        "item" -> items[id]?.let { "item." + it.lowercase().replace(' ', '_') }
        "npc" -> if (id in npcIds) "npc.npc_$id" else null
        else -> null
    }
}
