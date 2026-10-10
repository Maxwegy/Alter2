package org.alter.data.spawns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Where a spawn is (or should be) and how it behaves: the editable part of a [NpcSpawnEntry]. */
data class SpawnPlacement(val x: Int, val z: Int, val height: Int, val walkRadius: Int, val direction: String? = null) {
    companion object {
        fun of(entry: NpcSpawnEntry) = SpawnPlacement(entry.x, entry.z, entry.height, entry.walkRadius, entry.direction)
    }
}

/**
 * One in-game change to a spawn entry, as the live spawn commands record it in the runtime outbox
 * `data/run/spawn-edits.jsonl` (one JSON object per line). [from] is the entry as it was, [to] what it became,
 * or null when the spawn was removed. [regionId] is the region file [from] lives in and [source] the entry's
 * source when the edit was made: `"manual"` or the wiki page URL. [at] is an ISO-8601 instant.
 *
 * The server only appends these lines; `./gradlew :alter-data:spawnSync -PspawnArgs="--apply-edits"` applies
 * them to `data/cfg/spawns/npcs` ([SpawnEditApplier]).
 */
data class SpawnEdit(
    val at: String,
    val npc: String,
    val from: SpawnPlacement,
    val to: SpawnPlacement?,
    val regionId: Int,
    val source: String,
) {
    companion object {
        /** The edit that turns [entry] into [to] (null: remove it). */
        fun of(at: String, entry: NpcSpawnEntry, to: SpawnPlacement?) = SpawnEdit(
            at = at,
            npc = entry.npc,
            from = SpawnPlacement.of(entry),
            to = to,
            regionId = entry.regionId,
            source = when (val s = entry.source) {
                NpcSpawnSource.Manual -> NpcSpawnFiles.KIND_MANUAL
                is NpcSpawnSource.Edit -> NpcSpawnFiles.KIND_EDIT
                is NpcSpawnSource.Wiki -> s.page
            },
        )
    }
}

/** The outbox line format: fixed field order, `direction` omitted when absent, `to` written as `null` for a removal. */
object SpawnEdits {
    const val OUTBOX_FILE = "spawn-edits.jsonl"

    private val mapper = ObjectMapper()
    private val FIELDS = setOf("at", "npc", "from", "to", "regionId", "source")
    private val PLACEMENT_FIELDS = setOf("x", "z", "height", "walkRadius", "direction")

    /** One outbox line, without the line break. */
    fun line(edit: SpawnEdit): String {
        val root = mapper.createObjectNode()
        root.put("at", edit.at)
        root.put("npc", edit.npc)
        root.set<JsonNode>("from", placement(edit.from))
        if (edit.to == null) root.putNull("to") else root.set<JsonNode>("to", placement(edit.to))
        root.put("regionId", edit.regionId)
        root.put("source", edit.source)
        return mapper.writeValueAsString(root)
    }

    private fun placement(p: SpawnPlacement): ObjectNode = mapper.createObjectNode().apply {
        put("x", p.x)
        put("z", p.z)
        put("height", p.height)
        put("walkRadius", p.walkRadius)
        p.direction?.let { put("direction", it) }
    }

    /** A parsed outbox: [edits] with their 1-based line numbers, and one message per line that did not parse. */
    data class Parsed(val edits: List<IndexedValue<SpawnEdit>>, val errors: List<String>)

    /** Parses every non-blank line of [text]; a bad line is reported, never guessed at. */
    fun parse(text: String): Parsed {
        val edits = mutableListOf<IndexedValue<SpawnEdit>>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { i, raw ->
            val lineNo = i + 1
            if (raw.isBlank()) return@forEachIndexed
            val problems = mutableListOf<String>()
            val edit = parseLine(raw, problems)
            if (edit != null) edits += IndexedValue(lineNo, edit) else problems.forEach { errors += "line $lineNo: $it" }
        }
        return Parsed(edits, errors)
    }

    private fun parseLine(raw: String, problems: MutableList<String>): SpawnEdit? {
        val node = try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            problems += "not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}"
            return null
        }
        if (node !is ObjectNode) {
            problems += "must be a JSON object"
            return null
        }
        node.fieldNames().asSequence().filter { it !in FIELDS }.forEach { problems += "unknown field '$it'" }
        fun text(field: String): String? = node.get(field)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            .also { if (it == null) problems += "$field must be a non-empty string" }
        val at = text("at")
        val npc = text("npc")
        val source = text("source")
        val regionId = node.get("regionId")?.takeIf { it.isInt }?.intValue().also { if (it == null) problems += "regionId must be an integer" }
        val from = parsePlacement(node.get("from"), "from", problems)
        val toNode = node.get("to")
        val to = when {
            toNode == null -> null.also { problems += "to must be an object or null" }
            toNode.isNull -> null
            else -> parsePlacement(toNode, "to", problems)
        }
        if (problems.isNotEmpty()) return null
        return SpawnEdit(at!!, npc!!, from!!, to, regionId!!, source!!)
    }

    private fun parsePlacement(node: JsonNode?, name: String, problems: MutableList<String>): SpawnPlacement? {
        if (node !is ObjectNode) {
            problems += "$name must be an object"
            return null
        }
        val before = problems.size
        node.fieldNames().asSequence().filter { it !in PLACEMENT_FIELDS }.forEach { problems += "$name: unknown field '$it'" }
        fun int(field: String): Int? = node.get(field)?.takeIf { it.isInt }?.intValue().also { if (it == null) problems += "$name.$field must be an integer" }
        val x = int("x")
        val z = int("z")
        val height = int("height")
        val walkRadius = int("walkRadius")
        val direction = node.get("direction")?.let { d ->
            if (d.isTextual && d.textValue().isNotBlank()) d.textValue() else null.also { problems += "$name.direction must be a non-empty string when present" }
        }
        if (problems.size > before) return null
        return SpawnPlacement(x!!, z!!, height!!, walkRadius!!, direction)
    }
}
