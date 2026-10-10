package dev.openrune.cache.tools.rscm

import java.util.SortedMap
import java.util.TreeMap

/** A hand-made decision for one committed name, from `data/cfg/rscm-migrations/overrides.json`. */
data class RscmOverride(
    /** `map-to` (alias the name to [id]), `rename-alias` (alias [name] to the old id), or `drop`. */
    val action: String,
    val id: Int? = null,
    val name: String? = null,
    val reason: String? = null,
    /** The id the name had when the decision was made; the override is ignored for the same name on another id. */
    val oldId: Int? = null,
)

enum class Outcome {
    /** The committed name is the gameval name of the same id: nothing to emit. */
    IDENTICAL,
    /** The id still holds the same thing: alias `oldname:id`. */
    SAME_ID,
    /** The id holds something else now, and exactly one other id holds the old thing: alias `oldname:newId`. */
    REMAPPED,
    /** The id holds something else (or nothing) and no single id holds the old thing. Blocking when referenced. */
    UNRESOLVED,
    /** An unreferenced `null_<id>` name. (A referenced one is kept like any other name: content does use some.) */
    DROPPED_WAS_NULL,
    /** The committed name is the gameval name of a different id. The gameval meaning wins. Blocking when referenced. */
    CLASH,
    /** Decided by an override. */
    OVERRIDDEN,
}

data class MigrationEntry(
    val table: String,
    val oldName: String,
    val oldId: Int,
    val outcome: Outcome,
    /** The alias line that will be written, or null. */
    val alias: Pair<String, Int>?,
    val oldDisplay: String,
    val newDisplay: String?,
    val referenced: Boolean,
    val note: String = "",
) {
    val blocking: Boolean get() = referenced && (outcome == Outcome.UNRESOLVED || outcome == Outcome.CLASH)
}

class Migration(
    val table: RscmTable,
    /** id → gameval name: the canonical block. */
    val canonical: SortedMap<Int, String>,
    /** Alias lines in the order they are written. */
    val aliases: List<Pair<String, Int>>,
    val entries: List<MigrationEntry>,
) {
    val blocking: List<MigrationEntry> get() = entries.filter { it.blocking }
    fun count(outcome: Outcome) = entries.count { it.outcome == outcome }
}

/**
 * Decides, for every committed 228-era name, how it survives a cache whose canonical names are the gameval
 * names. Pure: takes maps, returns a [Migration]. All inputs are id → sanitized display base name except
 * [previous] (name → id, in file order) and [gameval] (id → gameval name).
 */
object RscmMigration {
    fun migrate(
        table: RscmTable,
        previous: Map<String, Int>,
        gameval: Map<Int, String>,
        newDisplay: Map<Int, String>,
        oldDisplay: Map<Int, String>,
        referenced: Set<String>,
        overrides: Map<String, RscmOverride>,
    ): Migration {
        val canonical: SortedMap<Int, String> = TreeMap(gameval)
        val gamevalByName = gameval.entries.associate { (id, name) -> name to id }
        val newDisplayIndex = newDisplay.entries.groupBy({ it.value }, { it.key })
        val claimed = mutableSetOf<Int>()
        val aliasNames = mutableSetOf<String>()
        val aliases = mutableListOf<Pair<String, Int>>()
        val entries = mutableListOf<MigrationEntry>()

        fun emit(entry: MigrationEntry) {
            val alias = entry.alias
            if (alias != null) {
                val (name, id) = alias
                val problem = when {
                    !RscmTables.NAME.matches(name) -> "alias `$name` is not a valid name"
                    id !in gameval -> "alias target $id has no gameval name"
                    name in gamevalByName -> "alias `$name` is a canonical name (of ${gamevalByName[name]})"
                    name in aliasNames -> "alias `$name` emitted twice"
                    else -> null
                }
                if (problem != null) {
                    entries += entry.copy(outcome = Outcome.UNRESOLVED, alias = null, note = problem)
                    return
                }
                aliasNames += name
                aliases += alias
            }
            entries += entry
        }

        for ((oldName, oldId) in previous) {
            val isReferenced = oldName in referenced
            val oldDisp = oldDisplay[oldId] ?: RscmTables.baseName(oldName)
            val newDisp = newDisplay[oldId]
            fun entry(outcome: Outcome, alias: Pair<String, Int>?, note: String = "") =
                MigrationEntry(table.rscm, oldName, oldId, outcome, alias, oldDisp, newDisp, isReferenced, note)

            val override = overrides["${table.rscm}.$oldName"]?.takeIf { it.oldId == null || it.oldId == oldId }
            if (override != null) {
                val reason = override.reason?.let { " ($it)" } ?: ""
                when (override.action) {
                    "map-to" -> emit(entry(Outcome.OVERRIDDEN, oldName to (override.id ?: error("map-to needs id for $oldName")), "map-to ${override.id}$reason"))
                    "rename-alias" -> emit(entry(Outcome.OVERRIDDEN, (override.name ?: error("rename-alias needs name for $oldName")) to oldId, "alias renamed to `${override.name}`$reason"))
                    "drop" -> emit(entry(Outcome.OVERRIDDEN, null, "dropped$reason"))
                    else -> error("Unknown override action '${override.action}' for ${table.rscm}.$oldName")
                }
                continue
            }
            when {
                gameval[oldId] == oldName -> emit(entry(Outcome.IDENTICAL, null))
                oldName in gamevalByName -> emit(entry(Outcome.CLASH, null, "gameval `$oldName` is id ${gamevalByName[oldName]} (${newDisplay[gamevalByName[oldName]]}); this id is now `${gameval[oldId]}`"))
                RscmTables.baseName(oldName) == "null" && !isReferenced -> emit(entry(Outcome.DROPPED_WAS_NULL, null))
                newDisp == oldDisp -> emit(entry(Outcome.SAME_ID, oldName to oldId))
                else -> {
                    val candidates = newDisplayIndex[oldDisp].orEmpty().filter { it != oldId && it !in claimed }
                    val holds = if (newDisp == null || newDisp == "null") "id $oldId is unnamed now" else "id $oldId now holds `$newDisp`"
                    if (candidates.size == 1) {
                        claimed += candidates[0]
                        emit(entry(Outcome.REMAPPED, oldName to candidates[0], "$holds; `$oldDisp` is ${candidates[0]} (${gameval[candidates[0]]})"))
                    } else {
                        emit(entry(Outcome.UNRESOLVED, null, "$holds; ${candidates.size} ids hold `$oldDisp`"))
                    }
                }
            }
        }
        return Migration(table, canonical, aliases, entries)
    }
}
