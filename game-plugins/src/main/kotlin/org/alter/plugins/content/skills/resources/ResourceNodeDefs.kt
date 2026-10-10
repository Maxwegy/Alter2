package org.alter.plugins.content.skills.resources

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The resource-node data model: `data/cfg/resources/resource_nodes.json` parsed into typed definitions. Every
 * number comes from the file (each entry cites an `oldschool.runescape.wiki` page); nothing is hard-coded here.
 * Object and item names are RSCM names resolved by [ResourceNodesService] at boot, so this file and
 * [ResourceRules] can be tested without a cache or a world. Entries, tools and tertiary blocks with a `todo` are
 * parsed (so their names stay checked) but never used.
 */

/** How a node's success chart is keyed: per tool tier (woodcutting) or one line for every tool (mining). */
enum class SuccessBy(val key: String) {
    TIER("tier"),
    LEVEL("level"),
    ;

    companion object {
        fun of(key: String?): SuccessBy = values().firstOrNull { it.key == key } ?: throw IllegalArgumentException("unknown successBy '$key'")
    }
}

/** A roll of [numerator] in [denominator] that uses [ticks] instead of the tool's interval (dragon and crystal pickaxes). */
data class FastRoll(val numerator: Int, val denominator: Int, val ticks: Int)

data class ToolDef(
    val item: String,
    val tier: String?,
    val level: Int,
    val animation: Int?,
    val rollIntervalTicks: Int?,
    val fastRoll: FastRoll?,
    val verify: Boolean,
    val source: String?,
    val notes: List<String>,
    val todo: String?,
) {
    val loaded: Boolean get() = todo.isNullOrBlank()
}

/** One row of the pre-roll table; a null [item] is the table's "Nothing" row. */
data class PreRollRow(val item: String?, val weight: Int)

/** A roll in `0 until outOf` made before the success roll; landing in [table] can replace the normal reward. */
data class PreRoll(val outOf: Int, val table: List<PreRollRow>, val source: String?) {
    val tableWeight: Int get() = table.sumOf { it.weight }
}

data class SkillDef(
    val key: String,
    val option: String,
    /** The roll interval for every tool (woodcutting), or null when each tool has its own (mining). */
    val rollIntervalTicks: Int?,
    val successBy: SuccessBy,
    val tools: List<ToolDef>,
    val preRoll: PreRoll?,
    val source: String?,
) {
    val loadedTools: List<ToolDef> get() = tools.filter { it.loaded }
}

data class NodeObject(val obj: String, val depleted: String, val verify: Boolean)

data class ObjectTodo(val obj: String, val todo: String)

/** When a successful roll depletes the node. */
sealed interface Depletion {
    object Always : Depletion

    /** The node depletes on the first success once [ticks] of chopping have passed (oak, willow). */
    data class Timer(val ticks: Int) : Depletion

    data class Chance(val numerator: Int, val denominator: Int) : Depletion
}

sealed interface Respawn {
    data class Fixed(val ticks: Int) : Respawn

    data class Range(val min: Int, val max: Int) : Respawn
}

data class Chart(val low: Int, val high: Int)

sealed interface Success {
    data class ByTier(val charts: Map<String, Chart>) : Success

    data class ByLevel(val chart: Chart) : Success
}

/** A roll made after a success; when it hits, [item] is given (instead of the reward when [replacesReward]). */
data class Tertiary(val numerator: Int, val denominator: Int, val item: String?, val replacesReward: Boolean, val todo: String?) {
    val loaded: Boolean get() = todo.isNullOrBlank()
}

data class NodeDef(
    val id: String,
    val skill: String,
    val level: Int,
    val experience: Double,
    val objects: List<NodeObject>,
    val objectsTodo: List<ObjectTodo>,
    val depletion: Depletion?,
    val respawn: Respawn?,
    val success: Success?,
    val reward: String?,
    val tertiary: List<Tertiary>,
    val source: String?,
    val notes: List<String>,
    val todo: String?,
) {
    val loaded: Boolean get() = todo.isNullOrBlank()
}

/** Chat lines; each is null while no sourced text exists, and then nothing is sent. */
data class ResourceMessages(
    val levelTooLow: String?,
    val noTool: String?,
    val toolLevelTooLow: String?,
    val inventoryFull: String?,
    val start: String?,
    val success: String?,
    val gem: String?,
    val depleted: String?,
) {
    val all: List<String?> get() = listOf(levelTooLow, noTool, toolLevelTooLow, inventoryFull, start, success, gem, depleted)
}

data class ResourceNodesTable(
    val skills: Map<String, SkillDef>,
    val nodes: List<NodeDef>,
    val messages: ResourceMessages,
    val todo: List<String> = emptyList(),
) {
    val loaded: List<NodeDef> get() = nodes.filter { it.loaded }
    val skipped: List<NodeDef> get() = nodes.filterNot { it.loaded }

    /** Object name to its loaded node and pairing; TODO entries are left out, so their objects stay unbound. */
    val byObject: Map<String, Pair<NodeDef, NodeObject>> = loaded.flatMap { n -> n.objects.map { it.obj to (n to it) } }.toMap()

    companion object {
        val EMPTY = ResourceNodesTable(emptyMap(), emptyList(), ResourceMessages(null, null, null, null, null, null, null, null))
    }
}

object ResourceNodeDefs {
    private const val FILE = "resource_nodes.json"
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(file: Path): ResourceNodesTable = parse(Files.readString(file))

    fun parse(json: String): ResourceNodesTable {
        val root: JsonNode = mapper.readTree(json)
        require(root["schemaVersion"]?.asInt() == 1) { "$FILE: unsupported schemaVersion ${root["schemaVersion"]}" }
        val m = root["messages"] ?: throw IllegalArgumentException("$FILE: messages is missing")
        val messages = ResourceMessages(
            text(m["levelTooLow"]), text(m["noTool"]), text(m["toolLevelTooLow"]), text(m["inventoryFull"]),
            text(m["start"]), text(m["success"]), text(m["gem"]), text(m["depleted"]),
        )
        val skillsNode = root["skills"] ?: throw IllegalArgumentException("$FILE: skills is missing")
        val skills = skillsNode.fields().asSequence().associate { (key, node) -> key to skill(key, node) }
        val nodes = root["nodes"]?.map { node(it, skills) } ?: throw IllegalArgumentException("$FILE: nodes is missing")
        require(nodes.map { it.id }.toSet().size == nodes.size) { "$FILE: a node id appears twice" }
        val objects = nodes.flatMap { n -> n.objects.map { it.obj } + n.objectsTodo.map { it.obj } }
        val twice = objects.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(twice.isEmpty()) { "$FILE: objects in more than one place: $twice" }
        val todo = root["todo"]?.map { it.asText() } ?: emptyList()
        return ResourceNodesTable(skills, nodes, messages, todo)
    }

    private fun text(n: JsonNode?): String? = n?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private fun int(n: JsonNode?): Int? = n?.takeIf { it.isNumber }?.asInt()

    private fun notes(n: JsonNode?): List<String> = n?.map { it.asText() } ?: emptyList()

    /** `[numerator, denominator]` with 0 < numerator <= denominator. */
    private fun ratio(id: String, n: JsonNode?): Pair<Int, Int> {
        require(n != null && n.isArray && n.size() == 2 && n.all { it.isInt }) { "$id: a chance must be [numerator, denominator]" }
        val (num, den) = n[0].asInt() to n[1].asInt()
        require(num in 1..den) { "$id: chance $num/$den is not in (0, 1]" }
        return num to den
    }

    private fun skill(key: String, node: JsonNode): SkillDef {
        val option = text(node["option"]) ?: throw IllegalArgumentException("$key: option is missing")
        val interval = int(node["rollIntervalTicks"])
        require(interval == null || interval > 0) { "$key: rollIntervalTicks must be positive" }
        val tools = node["tools"]?.map { tool(key, it) } ?: throw IllegalArgumentException("$key: tools is missing")
        val preRoll = node["preRoll"]?.takeUnless { it.isNull }?.let { p ->
            val outOf = int(p["outOf"]) ?: throw IllegalArgumentException("$key: preRoll.outOf is missing")
            val table = p["table"]?.map { r ->
                val weight = int(r["weight"]) ?: throw IllegalArgumentException("$key: a preRoll row has no weight")
                require(weight > 0) { "$key: a preRoll weight must be positive" }
                PreRollRow(text(r["item"]), weight)
            } ?: throw IllegalArgumentException("$key: preRoll.table is missing")
            PreRoll(outOf, table, text(p["source"])).also { require(it.tableWeight <= outOf) { "$key: preRoll table weighs more than outOf" } }
        }
        val def = SkillDef(key, option, interval, SuccessBy.of(text(node["successBy"])), tools, preRoll, text(node["source"]))
        require(def.loadedTools.isNotEmpty()) { "$key: no loaded tools" }
        def.loadedTools.forEach { t ->
            require(t.rollIntervalTicks != null || interval != null) { "$key: ${t.item} has no roll interval" }
            require(def.successBy != SuccessBy.TIER || t.tier != null) { "$key: ${t.item} needs a tier" }
        }
        require(def.loadedTools.mapNotNull { it.tier }.let { it.size == it.toSet().size }) { "$key: a tier appears twice" }
        return def
    }

    private fun tool(skill: String, node: JsonNode): ToolDef {
        val item = text(node["item"]) ?: throw IllegalArgumentException("$skill: a tool has no item")
        val fast = node["fastRoll"]?.takeUnless { it.isNull }?.let { f ->
            val (num, den) = ratio(item, f["chance"])
            val ticks = int(f["ticks"]) ?: throw IllegalArgumentException("$item: fastRoll.ticks is missing")
            require(ticks > 0) { "$item: fastRoll.ticks must be positive" }
            FastRoll(num, den, ticks)
        }
        val def = ToolDef(
            item = item,
            tier = text(node["tier"]),
            level = int(node["level"]) ?: 1,
            animation = int(node["animation"]),
            rollIntervalTicks = int(node["rollIntervalTicks"]),
            fastRoll = fast,
            verify = node["verify"]?.asBoolean() ?: false,
            source = text(node["source"]),
            notes = notes(node["notes"]),
            todo = text(node["todo"]),
        )
        if (def.loaded) {
            require(node["level"] != null && def.level in 1..99) { "$item: a loaded tool needs a level in 1..99" }
            require(def.animation != null) { "$item: a loaded tool needs an animation" }
            require(def.rollIntervalTicks == null || def.rollIntervalTicks > 0) { "$item: rollIntervalTicks must be positive" }
        }
        return def
    }

    private fun chart(id: String, n: JsonNode?): Chart {
        require(n != null && n.isArray && n.size() == 2 && n.all { it.isInt }) { "$id: a chart must be [low, high]" }
        val c = Chart(n[0].asInt(), n[1].asInt())
        require(c.low >= 0 && c.high >= c.low) { "$id: chart $c needs 0 <= low <= high" }
        return c
    }

    private fun node(n: JsonNode, skills: Map<String, SkillDef>): NodeDef {
        val id = text(n["id"]) ?: throw IllegalArgumentException("$FILE: a node has no id")
        val skillKey = text(n["skill"]) ?: throw IllegalArgumentException("$id: skill is missing")
        val skill = skills[skillKey] ?: throw IllegalArgumentException("$id: unknown skill '$skillKey'")
        val level = int(n["level"]) ?: throw IllegalArgumentException("$id: level is missing")
        require(level in 1..99) { "$id: level $level is outside 1..99" }
        val experience = n["experience"]?.takeIf { it.isNumber }?.asDouble() ?: throw IllegalArgumentException("$id: experience is missing")
        require(experience >= 0) { "$id: experience must not be negative" }
        val def = NodeDef(
            id = id,
            skill = skillKey,
            level = level,
            experience = experience,
            objects = n["objects"]?.map { o ->
                NodeObject(
                    text(o["object"]) ?: throw IllegalArgumentException("$id: an object has no name"),
                    text(o["depleted"]) ?: throw IllegalArgumentException("$id: ${o["object"]} has no depleted object"),
                    o["verify"]?.asBoolean() ?: false,
                )
            } ?: emptyList(),
            objectsTodo = n["objectsTodo"]?.map { o ->
                ObjectTodo(
                    text(o["object"]) ?: throw IllegalArgumentException("$id: an objectsTodo entry has no name"),
                    text(o["todo"]) ?: throw IllegalArgumentException("$id: objectsTodo ${o["object"]} needs a todo"),
                )
            } ?: emptyList(),
            depletion = depletion(id, n["depletion"]),
            respawn = respawn(id, n["respawnTicks"]),
            success = success(id, n["success"]),
            reward = text(n["reward"]),
            tertiary = n["tertiary"]?.map { t ->
                val (num, den) = ratio(id, t["chance"])
                Tertiary(num, den, text(t["item"]), t["replacesReward"]?.asBoolean() ?: false, text(t["todo"]))
                    .also { require(!it.loaded || it.item != null) { "$id: a loaded tertiary needs an item" } }
            } ?: emptyList(),
            source = text(n["source"]),
            notes = notes(n["notes"]),
            todo = text(n["todo"]),
        )
        if (def.loaded) {
            require(def.objects.isNotEmpty()) { "$id: a loaded node needs objects" }
            require(def.depletion != null) { "$id: a loaded node needs a depletion" }
            require(def.respawn != null) { "$id: a loaded node needs respawnTicks" }
            require(def.reward != null) { "$id: a loaded node needs a reward" }
            when (val s = def.success) {
                null -> throw IllegalArgumentException("$id: a loaded node needs a success chart")
                is Success.ByTier -> {
                    require(skill.successBy == SuccessBy.TIER) { "$id: skill $skillKey is charted by ${skill.successBy.key}, not tier" }
                    val missing = skill.loadedTools.mapNotNull { it.tier }.filter { it !in s.charts }
                    require(missing.isEmpty()) { "$id: no chart line for tiers $missing" }
                }
                is Success.ByLevel -> require(skill.successBy == SuccessBy.LEVEL) { "$id: skill $skillKey is charted by ${skill.successBy.key}, not level" }
            }
        }
        return def
    }

    private fun depletion(id: String, n: JsonNode?): Depletion? = when {
        n == null || n.isNull -> null
        n.isTextual && n.asText() == "always" -> Depletion.Always
        n.isObject && n.has("timerTicks") -> {
            val ticks = int(n["timerTicks"]) ?: throw IllegalArgumentException("$id: timerTicks must be a number")
            require(ticks > 0) { "$id: timerTicks must be positive" }
            Depletion.Timer(ticks)
        }
        n.isObject && n.has("chance") -> ratio(id, n["chance"]).let { (num, den) -> Depletion.Chance(num, den) }
        else -> throw IllegalArgumentException("$id: unknown depletion $n")
    }

    private fun respawn(id: String, n: JsonNode?): Respawn? = when {
        n == null || n.isNull -> null
        n.isInt -> Respawn.Fixed(n.asInt()).also { require(it.ticks > 0) { "$id: respawnTicks must be positive" } }
        n.isObject -> {
            val min = int(n["min"]) ?: throw IllegalArgumentException("$id: respawnTicks.min is missing")
            val max = int(n["max"]) ?: throw IllegalArgumentException("$id: respawnTicks.max is missing")
            require(min in 1..max) { "$id: respawnTicks needs 0 < min <= max" }
            Respawn.Range(min, max)
        }
        else -> throw IllegalArgumentException("$id: unknown respawnTicks $n")
    }

    private fun success(id: String, n: JsonNode?): Success? = when {
        n == null || n.isNull -> null
        n.has("byTier") -> Success.ByTier(n["byTier"].fields().asSequence().associate { (tier, c) -> tier to chart("$id.$tier", c) })
        n.has("byLevel") -> Success.ByLevel(chart(id, n["byLevel"]))
        else -> throw IllegalArgumentException("$id: success needs byTier or byLevel")
    }
}
