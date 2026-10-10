package dev.openrune.cache.tools.rscm

import dev.openrune.cache.tools.staging.Gameval
import gg.rsmod.util.Namer
import java.nio.file.Files
import java.nio.file.Path

/** The three RSCM tables content refers to, and the gameval group that names the same ids. */
enum class RscmTable(val rscm: String, val kind: Gameval.Kind) {
    ITEM("item", Gameval.Kind.OBJ),
    NPC("npc", Gameval.Kind.NPC),
    OBJECT("object", Gameval.Kind.LOC),
}

object RscmTables {
    /** Names must look like this to be usable from Kotlin string literals and the RSCM loader. */
    val NAME = Regex("^[a-z0-9_]+$")

    /** `data/cfg/rscm/<table>.rscm` lines are `name:id`; order is kept because the loader's reverse lookup is first-wins. */
    fun read(file: Path): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (!Files.exists(file)) return out
        Files.readAllLines(file).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || ':' !in line) return@forEach
            val id = line.substringAfterLast(':').trim().toIntOrNull() ?: return@forEach
            out[line.substringBeforeLast(':').trim()] = id
        }
        return out
    }

    /** The committed naming: `Namer` sanitizing (tags stripped, `_` for spaces, `[A-Za-z0-9_]` only), lowercased, no `_<id>` suffix. */
    fun displayBase(display: String?): String = if (display == null) "null" else Namer().name(display, 0)?.lowercase() ?: "null"

    /** A committed name without the `_<id>` suffix `Namer` adds to duplicates. */
    fun baseName(name: String): String = name.replace(Regex("_\\d+$"), "")

    /** `"item.shark"`-style references in Kotlin sources and JSON/YAML config under [roots], grouped by table. */
    fun references(roots: List<Path>): Map<RscmTable, Set<String>> {
        val pattern = Regex("\"(item|npc|object)\\.([a-z0-9_]+)\"")
        val found = RscmTable.values().associateWith { mutableSetOf<String>() }
        for (root in roots) {
            if (!Files.exists(root)) continue
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { p -> val n = p.fileName.toString(); n.endsWith(".kt") || n.endsWith(".json") || n.endsWith(".yml") || n.endsWith(".yaml") }
                    .filter { p -> !p.toString().replace('\\', '/').contains("/data/cfg/wiki/") }
                    .forEach { p ->
                        pattern.findAll(Files.readString(p, Charsets.UTF_8)).forEach { m ->
                            val table = RscmTable.values().first { it.rscm == m.groupValues[1] }
                            found.getValue(table) += m.groupValues[2]
                        }
                    }
            }
        }
        return found
    }
}
