package org.alter.data.drops

import org.alter.data.cache.CacheView
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.bool
import org.alter.data.wiki.ids
import org.alter.data.wiki.str

/**
 * Resolves the item names used in drop tables to canonical item ids in our cache, in this order:
 * 1. an exact `infobox_item.page_name_sub` match (handles names like "Zombie bone#Unpolished")
 * 2. the default version of an `infobox_item.page_name`
 * 3. `infobox_item.item_name`
 * 4. our cache's own item names
 * Noted and placeholder ids are never canonical; among candidates the lowest id wins.
 */
class ItemNameResolver(itemRows: List<BucketRow>, private val cache: CacheView) {
    private val bySub = HashMap<String, MutableList<Int>>()
    private val byDefaultPage = HashMap<String, MutableList<Int>>()
    private val byName = HashMap<String, MutableList<Int>>()

    init {
        itemRows.forEach { row ->
            val ids = row.ids("item_id")
            if (ids.isEmpty()) return@forEach
            row.str("page_name_sub")?.let { bySub.getOrPut(it.lowercase(), ::mutableListOf) += ids }
            if (row.bool("default_version") != false) {
                row.str("page_name")?.let { byDefaultPage.getOrPut(it.lowercase(), ::mutableListOf) += ids }
            }
            row.str("item_name")?.let { byName.getOrPut(it.lowercase(), ::mutableListOf) += ids }
        }
    }

    fun resolve(name: String): Int? {
        val key = name.trim().lowercase()
        return pick(bySub[key]) ?: pick(byDefaultPage[key]) ?: pick(byName[key])
            ?: pick(cache.itemIdsByName(name.substringBefore('#').trim()))
    }

    private fun pick(ids: List<Int>?): Int? =
        ids?.filter { cache.hasItem(it) && !cache.isNotedOrPlaceholder(it) }?.minOrNull()

    companion object {
        val FIELDS = listOf("page_name", "page_name_sub", "item_name", "item_id", "default_version")
    }
}
