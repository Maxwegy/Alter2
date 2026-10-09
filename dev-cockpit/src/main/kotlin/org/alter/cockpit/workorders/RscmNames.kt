package org.alter.cockpit.workorders

import java.nio.file.Files
import java.nio.file.Path

/**
 * The committed RSCM name maps (`data/cfg/rscm/npc.rscm`: `man_3108:3108` per line), so generated content
 * refers to things by name as the project rules require. Loaded lazily, once per namespace.
 */
class RscmNames(private val dir: Path) {
    private val byId = mutableMapOf<String, Map<Int, String>>()
    private val byName = mutableMapOf<String, Map<String, Int>>()

    /** `npc.man_3108` for npc id 3108, or null when the id is not in the map. */
    fun name(namespace: String, id: Int): String? = idsOf(namespace)[id]?.let { "$namespace.$it" }

    /** The best name for a wiki page name, e.g. "Bread dough" → `item.bread_dough`: an exact slug, else the lowest-id `slug_<id>`. */
    fun nameFor(namespace: String, pageName: String): String? {
        val slug = slug(pageName)
        val names = namesOf(namespace)
        if (slug in names) return "$namespace.$slug"
        return names.keys.filter { it.startsWith("${slug}_") && it.substringAfterLast('_').toIntOrNull() != null }
            .minByOrNull { names.getValue(it) }?.let { "$namespace.$it" }
    }

    private fun idsOf(namespace: String): Map<Int, String> = byId.getOrPut(namespace) { namesOf(namespace).entries.associate { (name, id) -> id to name } }

    private fun namesOf(namespace: String): Map<String, Int> = byName.getOrPut(namespace) { load(namespace) }

    private fun load(namespace: String): Map<String, Int> {
        val file = dir.resolve("$namespace.rscm")
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).mapNotNull { line ->
            val sep = line.lastIndexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val id = line.substring(sep + 1).trim().toIntOrNull() ?: return@mapNotNull null
            line.substring(0, sep).trim() to id
        }.toMap()
    }

    companion object {
        fun slug(name: String): String = name.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
    }
}
