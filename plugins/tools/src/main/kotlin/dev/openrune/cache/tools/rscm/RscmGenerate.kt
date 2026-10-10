package dev.openrune.cache.tools.rscm

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.definition.data.ItemType
import dev.openrune.cache.filestore.definition.decoder.ItemDecoder
import dev.openrune.cache.filestore.definition.decoder.NPCDecoder
import dev.openrune.cache.filestore.definition.decoder.ObjectDecoder
import dev.openrune.cache.tools.staging.Gameval
import org.alter.data.report.Report
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Everything one generator run produced, for the CLI, the tests and the report. */
class Generated(
    val migrations: Map<RscmTable, Migration>,
    val oldDisplay: Map<RscmTable, Map<Int, String>>,
    val newDisplay: Map<RscmTable, Map<Int, String>>,
    val oldDisplayFromCache: Boolean,
    val out: Path,
) {
    val blocking: List<MigrationEntry> get() = migrations.values.flatMap { it.blocking }

    /** What the RSCM loader would resolve `table.name` to after loading the generated files (canonical first, then aliases). */
    fun resolve(table: RscmTable, name: String): Int? {
        val m = migrations.getValue(table)
        return m.canonical.entries.firstOrNull { it.value == name }?.key ?: m.aliases.firstOrNull { it.first == name }?.second
    }
}

/**
 * Generates `data/cfg/rscm` for a cache whose canonical names are the gameval names (index 24), keeping every
 * committed name working through generated aliases. See `docs/phase-1.5-cache-241.md` and the plan in
 * `docs/rscm-241-migration.md`. Writes into [out]; nothing under `data/cfg` changes unless [commit] is called.
 */
class RscmGenerate(
    private val cacheDir: Path,
    private val build: Int,
    private val previousRscmDir: Path,
    private val previousCacheDir: Path?,
    private val previousBuild: Int,
    private val overridesFile: Path?,
    private val referenceRoots: List<Path>,
    val out: Path,
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun run(): Generated {
        val overrides: Map<String, RscmOverride> = overridesFile?.takeIf(Files::exists)?.let {
            gson.fromJson(Files.readString(it), object : TypeToken<Map<String, RscmOverride>>() {}.type)
        } ?: emptyMap()
        val previous = RscmTable.values().associateWith { RscmTables.read(previousRscmDir.resolve("${it.rscm}.rscm")) }
        val referenced = RscmTables.references(referenceRoots)

        // The 228-era display names, decoded straight from the previous cache so the singleton CacheManager is not polluted.
        val oldFromCache = previousCacheDir != null && Files.exists(previousCacheDir.resolve("main_file_cache.idx255"))
        val oldDisplay: Map<RscmTable, Map<Int, String>> = if (oldFromCache) decodeDisplayNames(previousCacheDir!!, previousBuild) else
            previous.mapValues { (_, names) -> names.entries.associate { (name, id) -> id to RscmTables.baseName(name) } }

        CacheManager.init(cacheDir, build)
        val gameval = Gameval.readAll(CacheManager.cache)
        val newDisplay = mapOf(
            RscmTable.ITEM to itemDisplayNames(CacheManager.getItems()),
            RscmTable.NPC to CacheManager.getNpcs().mapValues { RscmTables.displayBase(it.value.name) },
            RscmTable.OBJECT to CacheManager.getObjects().mapValues { RscmTables.displayBase(it.value.name) },
        )
        val migrations = RscmTable.values().associateWith { table ->
            RscmMigration.migrate(
                table, previous.getValue(table), gameval.getValue(table.kind), newDisplay.getValue(table),
                oldDisplay.getValue(table), referenced.getValue(table), overrides,
            )
        }
        val generated = Generated(migrations, oldDisplay, newDisplay, oldFromCache, out)
        write(generated)
        return generated
    }

    private fun decodeDisplayNames(dir: Path, revision: Int): Map<RscmTable, Map<Int, String>> {
        // The decoders enumerate ids through CacheManager.cache and gate opcodes on CacheManager.cacheRevision, so
        // point both at the previous cache for the duration; CacheManager.init(...) for the new cache follows.
        CacheManager.cacheRevision = revision
        val cache = Cache.load(dir, false)
        CacheManager.cache = cache
        try {
            return mapOf(
                RscmTable.ITEM to itemDisplayNames(ItemDecoder().load(cache)),
                RscmTable.NPC to NPCDecoder().load(cache).mapValues { RscmTables.displayBase(it.value.name) },
                RscmTable.OBJECT to ObjectDecoder().load(cache).mapValues { RscmTables.displayBase(it.value.name) },
            )
        } finally {
            cache.close()
        }
    }

    /** Same rule as the committed tables: a noted item is named after what it notes, plus `_NOTED`. */
    private fun itemDisplayNames(items: Map<Int, ItemType>): Map<Int, String> = items.mapValues { (_, item) ->
        RscmTables.displayBase(if (item.noteTemplateId > 0) (items[item.noteLinkId]?.name ?: "null") + "_NOTED" else item.name)
    }

    // ---- outputs -------------------------------------------------------------------------------------------

    private fun write(g: Generated) {
        val rscmDir = out.resolve("rscm").also(Files::createDirectories)
        val metaDir = out.resolve("rscm-meta").also(Files::createDirectories)
        val migrationsDir = out.resolve("rscm-migrations").also(Files::createDirectories)
        val tables = linkedMapOf<String, Any>()
        for ((table, m) in g.migrations) {
            val lines = rscmLines(m)
            val file = rscmDir.resolve("${table.rscm}.rscm")
            writeLf(file, lines)
            writeLf(metaDir.resolve("${table.rscm}.tsv"), tsvLines(m, g.newDisplay.getValue(table)))
            tables[table.rscm] = linkedMapOf("canonical" to m.canonical.size, "aliases" to m.aliases.size, "sha256" to sha256(file))
        }
        val manifest = linkedMapOf(
            "build" to build,
            "cacheDir" to cacheDir.fileName.toString(),
            "previousBuild" to previousBuild,
            "oldDisplayNamesFromCache" to g.oldDisplayFromCache,
            "tables" to tables,
        )
        writeLf(metaDir.resolve("manifest.json"), listOf(gson.toJson(manifest)))
        val mutated = g.migrations.values.flatMap { m -> m.entries.filter { it.outcome != Outcome.IDENTICAL && it.outcome != Outcome.SAME_ID } }
        writeLf(migrationsDir.resolve("$previousBuild-to-$build.json"), listOf(gson.toJson(mutated.map { e ->
            linkedMapOf("table" to e.table, "oldName" to e.oldName, "oldId" to e.oldId, "outcome" to e.outcome.name, "alias" to e.alias?.first,
                "newId" to e.alias?.second, "oldDisplay" to e.oldDisplay, "newDisplay" to e.newDisplay, "referenced" to e.referenced, "note" to e.note)
        })))
        writeLf(out.resolve("migration.md"), markdown(g).lines())
    }

    /** Canonical block sorted by id, then the alias block sorted by id and name. Pure, so the tests can check it. */
    internal fun rscmLines(m: Migration): List<String> =
        m.canonical.map { (id, name) -> "$name:$id" } + m.aliases.sortedWith(compareBy({ it.second }, { it.first })).map { (name, id) -> "$name:$id" }

    private fun tsvLines(m: Migration, display: Map<Int, String>): List<String> =
        listOf("id\tname\tdisplay\trow") +
            m.canonical.map { (id, name) -> "$id\t$name\t${display[id] ?: ""}\tcanonical" } +
            m.aliases.sortedWith(compareBy({ it.second }, { it.first })).map { (name, id) -> "$id\t$name\t${display[id] ?: ""}\talias" }

    private fun markdown(g: Generated): String = buildString {
        appendLine("# RSCM migration $previousBuild → $build")
        appendLine()
        appendLine("Generated by `rscmGenerate` from `${cacheDir.fileName}`. Canonical names are the gameval names (index 24); every committed")
        appendLine("$previousBuild name is kept as an alias unless listed below. Old display names ${if (g.oldDisplayFromCache) "come from the $previousBuild cache" else "are the committed names without their `_<id>` suffix (no $previousBuild cache was staged)"}.")
        appendLine()
        appendLine("| table | canonical | aliases | identical | same id | remapped | unresolved | clash | was null | overridden | blocking |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for ((table, m) in g.migrations) {
            appendLine("| ${table.rscm} | ${m.canonical.size} | ${m.aliases.size} | ${m.count(Outcome.IDENTICAL)} | ${m.count(Outcome.SAME_ID)} | ${m.count(Outcome.REMAPPED)} | ${m.count(Outcome.UNRESOLVED)} | ${m.count(Outcome.CLASH)} | ${m.count(Outcome.DROPPED_WAS_NULL)} | ${m.count(Outcome.OVERRIDDEN)} | ${m.blocking.size} |")
        }
        val blocking = g.blocking
        appendLine()
        appendLine("## Blocking (referenced by content, needs a decision in `data/cfg/rscm-migrations/overrides.json`) (${blocking.size})")
        appendLine()
        if (blocking.isEmpty()) appendLine("_None._")
        blocking.forEach { appendLine("- `${it.table}.${it.oldName}` (${it.oldId}): ${it.outcome} — ${it.note}") }
        // Unreferenced `null_<id>` drops are counted above, not listed: tens of thousands of lines nobody can act on.
        for (outcome in listOf(Outcome.OVERRIDDEN, Outcome.CLASH, Outcome.REMAPPED, Outcome.UNRESOLVED)) {
            for ((table, m) in g.migrations) {
                val rows = m.entries.filter { it.outcome == outcome }
                if (rows.isEmpty()) continue
                appendLine()
                appendLine("## ${table.rscm}: $outcome (${rows.size})")
                appendLine()
                rows.forEach { e ->
                    val target = e.alias?.let { "→ `${it.first}:${it.second}`" } ?: "→ no alias"
                    appendLine("- `${e.oldName}` (${e.oldId}, was `${e.oldDisplay}`) $target${if (e.referenced) " **referenced**" else ""}${if (e.note.isNotEmpty()) ": ${e.note}" else ""}")
                }
            }
        }
    }

    fun report(g: Generated): Report = Report(
        tool = "rscm-generate-$build",
        summary = linkedMapOf<String, Any>("cacheDir" to cacheDir.toString(), "build" to build, "previousBuild" to previousBuild, "out" to out.toString(), "blocking" to g.blocking.size) +
            g.migrations.flatMap { (t, m) -> Outcome.values().map { "${t.rscm}.${it.name.lowercase()}" to m.count(it) } + listOf("${t.rscm}.canonical" to m.canonical.size, "${t.rscm}.aliases" to m.aliases.size) },
        sections = listOf(Report.Section("Blocking", g.blocking.map { "${it.table}.${it.oldName} (${it.oldId}): ${it.outcome} ${it.note}" }, Report.Severity.ERROR)) +
            g.migrations.flatMap { (t, m) ->
                listOf(Outcome.REMAPPED, Outcome.UNRESOLVED, Outcome.CLASH, Outcome.OVERRIDDEN).map { o ->
                    Report.Section("${t.rscm} $o", m.entries.filter { it.outcome == o }.map { "${it.oldName} (${it.oldId}) ${it.alias?.let { a -> "-> ${a.first}:${a.second}" } ?: ""} ${it.note}".trim() }, if (o == Outcome.UNRESOLVED || o == Outcome.CLASH) Report.Severity.WARNING else Report.Severity.INFO)
                }
            },
    )

    /** Copies the generated tables into `data/cfg`. Refused while anything is blocking. */
    fun commit(g: Generated, dataDir: Path) {
        check(g.blocking.isEmpty()) { "${g.blocking.size} blocking entries; decide them in overrides.json first" }
        for (sub in listOf("rscm", "rscm-meta", "rscm-migrations")) {
            val target = dataDir.resolve("cfg/$sub").also(Files::createDirectories)
            Files.list(out.resolve(sub)).use { files -> files.forEach { Files.copy(it, target.resolve(it.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) } }
        }
    }

    private fun writeLf(file: Path, lines: List<String>) {
        Files.writeString(file, lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
