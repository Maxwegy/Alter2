package org.alter.data.cache

import dev.openrune.cache.CacheManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * [CacheView] over an initialised CacheManager plus the committed RSCM files (`data/cfg/rscm/<table>.rscm`).
 * Builds its lookup maps once; RSCM's own reverse lookup scans every entry per call.
 */
class CacheManagerView(override val revision: Int, rscmDir: Path) : CacheView {
    private val items = CacheManager.getItems()
    private val npcs = CacheManager.getNpcs()

    private val canonicalByName: Map<String, List<Int>> = items.values
        .filter { !it.noted && !it.isPlaceholder && it.name != "null" }
        .groupBy({ it.name.lowercase() }, { it.id })
        .mapValues { (_, ids) -> ids.sorted() }

    private val rscm: Map<String, Map<Int, String>> = loadRscm(rscmDir)

    override fun hasNpc(id: Int) = id in npcs

    override fun hasItem(id: Int) = id in items

    override fun itemName(id: Int) = items[id]?.name

    override fun isNotedOrPlaceholder(id: Int) = items[id]?.let { it.noted || it.isPlaceholder } ?: false

    override fun notedId(id: Int): Int? {
        val item = items[id] ?: return null
        val link = item.noteLinkId
        return link.takeIf { it > 0 && items[it]?.noted == true }
    }

    override fun itemIdsByName(name: String) = canonicalByName[name.lowercase()].orEmpty()

    override fun rscmName(table: String, id: Int) = rscm[table]?.get(id)?.let { "$table.$it" }

    private fun loadRscm(dir: Path): Map<String, Map<Int, String>> {
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".rscm") }.toList()
        }.associate { file ->
            val table = file.fileName.toString().removeSuffix(".rscm")
            val byId = HashMap<Int, String>()
            Files.readAllLines(file).forEach { line ->
                val parts = line.split(':')
                if (parts.size == 2) {
                    parts[1].trim().toIntOrNull()?.let { id -> byId.putIfAbsent(id, parts[0].trim()) }
                }
            }
            table to byId
        }
    }
}
