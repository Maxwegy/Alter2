package org.alter.data.cache

/**
 * What the data layer needs to know about the server's own OSRS cache. The real implementation wraps
 * CacheManager and the RSCM files; tests use a fake. Keeping this an interface keeps alter-data testable
 * without a cache on disk.
 */
interface CacheView {
    val revision: Int

    fun hasNpc(id: Int): Boolean

    fun hasItem(id: Int): Boolean

    fun itemName(id: Int): String?

    /** True for noted variants and bank placeholders, which are never the canonical id of an item. */
    fun isNotedOrPlaceholder(id: Int): Boolean

    /** The noted variant of [id], or null if it has none. */
    fun notedId(id: Int): Int?

    /** Canonical item ids whose cache name equals [name] (case-insensitive), ascending. */
    fun itemIdsByName(name: String): List<Int>

    /** RSCM name such as `item.abyssal_whip` or `npc.abyssal_demon_415`, for human review only. */
    fun rscmName(table: String, id: Int): String?
}
