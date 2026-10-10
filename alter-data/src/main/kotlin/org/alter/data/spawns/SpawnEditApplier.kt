package org.alter.data.spawns

import org.alter.data.report.Report
import org.alter.data.report.ReportBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Applies the runtime outbox (`data/run/spawn-edits.jsonl`, written by the live spawn commands) to the region
 * files in `data/cfg/spawns/npcs`. Offline: it reads only those two places and never the wiki.
 *
 * Edits apply in outbox order, so a later edit of a moved spawn matches the earlier edit's `to`. Each edit
 * matches the entry with the same `(npc, from.x, from.z, from.height)`:
 * - `to: null` deletes the entry;
 * - otherwise the entry takes `to`'s tile, walk radius and direction, moving to another region file when the
 *   region changes; an edited wiki entry becomes `source: "manual"` with `origin` set to its page, so the
 *   generator keeps it and drops its wiki twin when they overlap.
 *
 * An edit with no matching entry, or one that would put the same NPC twice on a tile, is reported and skipped,
 * never guessed at. The applied outbox is kept as `spawn-edits.applied-<ts>.jsonl` (with every line, including
 * the skipped ones).
 */
object SpawnEditApplier {
    const val TOOL = "spawn-apply-edits"

    data class Unmatched(val line: Int, val edit: SpawnEdit, val reason: String) {
        override fun toString(): String = String.format(
            "line %04d: %s from (%d, %d, %d): %s",
            line, edit.npc, edit.from.x, edit.from.z, edit.from.height, reason,
        )
    }

    data class Applied(val entries: List<NpcSpawnEntry>, val applied: Int, val moved: Int, val deleted: Int, val unmatched: List<Unmatched>)

    /** Pure: applies [edits] (with their line numbers) to [entries]. */
    fun apply(entries: List<NpcSpawnEntry>, edits: List<IndexedValue<SpawnEdit>>): Applied {
        val byKey = LinkedHashMap<NpcSpawnEntry.Key, NpcSpawnEntry>()
        entries.forEach { byKey[it.key] = it }
        val unmatched = mutableListOf<Unmatched>()
        var applied = 0
        var moved = 0
        var deleted = 0
        edits.forEach { (line, edit) ->
            val key = NpcSpawnEntry.Key(edit.npc, edit.from.x, edit.from.z, edit.from.height)
            val entry = byKey[key]
            if (entry == null) {
                unmatched += Unmatched(line, edit, "no entry for this npc on that tile")
                return@forEach
            }
            val to = edit.to
            if (to == null) {
                byKey.remove(key)
                applied++
                deleted++
                return@forEach
            }
            invalid(to)?.let {
                unmatched += Unmatched(line, edit, it)
                return@forEach
            }
            val edited = entry.copy(
                x = to.x,
                z = to.z,
                height = to.height,
                walkRadius = to.walkRadius,
                direction = to.direction,
                source = editedSource(entry.source, edit.at),
            )
            if (edited.key != key && byKey.containsKey(edited.key)) {
                unmatched += Unmatched(line, edit, "${edit.npc} already spawns at (${to.x}, ${to.z}, ${to.height})")
                return@forEach
            }
            byKey.remove(key)
            byKey[edited.key] = edited
            applied++
            if (edited.regionId != entry.regionId) moved++
        }
        return Applied(byKey.values.sortedWith(NpcSpawnFiles.canonicalOrder), applied, moved, deleted, unmatched)
    }

    /** An edited entry is an edit entry that keeps the wiki page and map it was first generated from. */
    fun editedSource(source: NpcSpawnSource, at: String): NpcSpawnSource.Edit = when (source) {
        NpcSpawnSource.Manual -> NpcSpawnSource.Edit(at)
        is NpcSpawnSource.Edit -> NpcSpawnSource.Edit(at, source.page, source.map)
        is NpcSpawnSource.Wiki -> NpcSpawnSource.Edit(at, source.page, source.map)
    }

    private fun invalid(to: SpawnPlacement): String? = when {
        to.x !in 0..NpcSpawnFiles.MAX_COORDINATE || to.z !in 0..NpcSpawnFiles.MAX_COORDINATE -> "to is off the map"
        to.height !in 0..NpcSpawnFiles.MAX_HEIGHT -> "to.height ${to.height} is not 0..${NpcSpawnFiles.MAX_HEIGHT}"
        to.walkRadius < 0 -> "to.walkRadius ${to.walkRadius} is negative"
        else -> null
    }

    sealed interface Result {
        val report: Report

        /** There was no outbox, or it held no lines. */
        data class NothingToApply(override val report: Report) : Result

        /** Applied and written; the outbox was renamed to [archived]. */
        data class Written(val applied: Applied, val write: NpcSpawnFiles.WriteResult, val archived: Path, override val report: Report) : Result

        /** Nothing was written and the outbox was left in place: [reason] says why. */
        data class Rejected(val reason: String, override val report: Report) : Result
    }

    /**
     * Applies [outbox] to [spawnDir]; [now] names the archived outbox. The outbox is renamed before it is read,
     * so lines a running server appends meanwhile start a fresh outbox instead of being archived unapplied.
     * A rejected run moves it back (or, when a fresh outbox already exists, leaves it under its archive name and
     * says so in the reason).
     */
    fun run(spawnDir: Path, outbox: Path, now: Instant = Instant.now()): Result {
        val report = ReportBuilder(TOOL)
        report.summary("outbox", outbox.fileName.toString())
        if (!Files.isRegularFile(outbox)) {
            report.summary("edits", 0)
            return Result.NothingToApply(report.build())
        }
        val archived = outbox.resolveSibling("spawn-edits.applied-${STAMP.format(now)}.jsonl")
        Files.move(outbox, archived)
        fun reject(reason: String): Result {
            val restored = if (Files.exists(outbox)) {
                "$reason The edits are kept in ${archived.fileName}, because a new ${outbox.fileName} was started meanwhile."
            } else {
                Files.move(archived, outbox)
                reason
            }
            report.add("Apply rejected", restored, Report.Severity.ERROR)
            return Result.Rejected(restored, report.build())
        }

        val parsed = SpawnEdits.parse(Files.readString(archived))
        report.summary("edits", parsed.edits.size)
        report.summary("unreadableLines", parsed.errors.size)
        parsed.errors.forEach { report.add("Unreadable outbox lines", it, Report.Severity.ERROR) }
        if (parsed.errors.isNotEmpty()) return reject("${parsed.errors.size} outbox line(s) do not parse; fix or remove them first.")
        if (parsed.edits.isEmpty()) {
            if (!Files.exists(outbox)) Files.move(archived, outbox)
            return Result.NothingToApply(report.build())
        }

        val existing = when (val read = NpcSpawnFiles.readAll(spawnDir)) {
            NpcSpawnFiles.ReadResult.Missing -> return reject("$spawnDir does not exist.")
            is NpcSpawnFiles.ReadResult.Read -> {
                if (read.errors.isNotEmpty()) {
                    read.errors.forEach { report.add("Region file errors", it, Report.Severity.ERROR) }
                    return reject("${read.errors.size} problem(s) in the existing region files; fix them first.")
                }
                read.entries
            }
        }

        val applied = apply(existing, parsed.edits)
        report.summary("applied", applied.applied)
        report.summary("moved", applied.moved)
        report.summary("deleted", applied.deleted)
        report.summary("unmatched", applied.unmatched.size)
        applied.unmatched.forEach { report.add("Unmatched edits (not applied)", it.toString()) }

        val files = NpcSpawnFiles.group(applied.entries)
        val invalid = mutableListOf<String>()
        files.forEach { NpcSpawnFiles.parse("${it.regionId}.json", NpcSpawnFiles.render(it), invalid) }
        if (invalid.isNotEmpty()) {
            invalid.forEach { report.add("Edited entries the reader rejects", it, Report.Severity.ERROR) }
            return reject("${invalid.size} problem(s) after applying the edits; region files left untouched.")
        }
        val written = NpcSpawnFiles.write(spawnDir, files)
        report.summary("filesWritten", written.written.size)
        report.summary("filesRemoved", written.removed.size)
        written.written.forEach { report.add("Changed files", it, Report.Severity.INFO) }
        written.removed.forEach { report.add("Removed files", it, Report.Severity.INFO) }
        report.summary("archivedAs", archived.fileName.toString())
        return Result.Written(applied, written, archived, report.build())
    }

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
}
