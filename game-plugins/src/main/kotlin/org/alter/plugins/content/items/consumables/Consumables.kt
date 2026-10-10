package org.alter.plugins.content.items.consumables

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.alter.api.Skills
import org.alter.game.model.timer.COMBO_FOOD_DELAY
import org.alter.game.model.timer.FOOD_DELAY
import org.alter.game.model.timer.POTION_DELAY
import org.alter.game.model.timer.TimerKey
import java.nio.file.Files
import java.nio.file.Path

/**
 * The consumable framework's data model: `data/cfg/consumables/consumables.json` parsed into typed definitions.
 * Every number comes from the file (each entry cites an `oldschool.runescape.wiki` page); nothing is hard-coded
 * here. Item names are RSCM names and are resolved to ids by [ConsumablesService] at boot, so this file and the
 * rules in [ConsumptionRules] can be tested without a cache or a world.
 */

/** Which delay timer gates the consumable and which it sets; see `docs/roadmap-pillars.md` §3. */
enum class Kind(val key: String, val gate: TimerKey, val verb: String) {
    FOOD("food", FOOD_DELAY, "eat"),
    COMBO("combo", COMBO_FOOD_DELAY, "eat"),
    POTION("potion", POTION_DELAY, "drink"),
    /** Barbarian mixes: potions that use the food timer. */
    MIX("mix", FOOD_DELAY, "drink"),
    ;

    companion object {
        fun of(key: String): Kind = values().firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown consumable kind '$key'")
    }
}

sealed interface Heal {
    val overheal: Boolean

    /** The amount for a player whose base Hitpoints level is [baseHitpoints]; [roll] in `[0, 1)` for ranges. */
    fun amount(baseHitpoints: Int, roll: Double): Int

    data class Fixed(val amount: Int, override val overheal: Boolean) : Heal {
        override fun amount(baseHitpoints: Int, roll: Double) = amount
    }

    /** `plus + floor(base * percent / 100)`, e.g. Saradomin brew 2 + 15%. */
    data class PercentOfBase(val percent: Int, val plus: Int, override val overheal: Boolean) : Heal {
        override fun amount(baseHitpoints: Int, roll: Double) = plus + baseHitpoints * percent / 100
    }

    /** A table by base Hitpoints level, e.g. anglerfish. */
    data class Brackets(val brackets: List<Bracket>, override val overheal: Boolean) : Heal {
        data class Bracket(val minLevel: Int, val maxLevel: Int, val amount: Int)

        override fun amount(baseHitpoints: Int, roll: Double) =
            brackets.firstOrNull { baseHitpoints in it.minLevel..it.maxLevel }?.amount ?: 0
    }

    /** A uniform random amount, e.g. cave eel 8 to 12. */
    data class Range(val min: Int, val max: Int, override val overheal: Boolean) : Heal {
        override fun amount(baseHitpoints: Int, roll: Double) = min + (roll * (max - min + 1)).toInt().coerceIn(0, max - min)
    }
}

sealed interface Effect {
    /** `plus + floor(base * percent / 100)` above base, capped at base + that amount. */
    data class Boost(val skills: List<Int>, val percentOfBase: Int, val plus: Int) : Effect {
        fun amount(base: Int) = plus + base * percentOfBase / 100
    }

    /** `plus + floor(current * percent / 100)` below current, never under 0. */
    data class Drain(val skills: List<Int>, val percentOfCurrent: Int, val plus: Int) : Effect {
        fun amount(current: Int) = plus + current * percentOfCurrent / 100
    }

    /** Lowered skills come back toward base by `plus + floor(base * percent / 100)`; Prayer may use [prayerGearPercent]. */
    data class Restore(val target: Target, val percentOfBase: Int, val plus: Int, val prayerGearPercent: Int?) : Effect {
        sealed interface Target {
            data class Skills(val skills: List<Int>) : Target
            object AllLoweredExceptHitpoints : Target
        }

        fun amount(base: Int, percent: Int) = plus + base * percent / 100
    }
}

data class Consumable(
    val item: String,
    val kind: Kind,
    val heal: Heal?,
    val effects: List<Effect>,
    val replacement: String?,
    val delayTicks: Int,
    val attackDelayTicks: Int,
    val animation: Int,
    val sound: Int,
    val source: String?,
) {
    /** `saradomin_brew3` → 3; a replacement that is not a dose (vial, half pizza) → null. */
    val replacementDoses: Int? get() = replacement?.let { Regex("(\\d)$").find(it)?.groupValues?.get(1)?.toInt() }
}

data class PrayerGear(val worn: List<String>, val carried: List<String>)

data class ConsumablesTable(
    val defaults: Map<Kind, KindDefaults>,
    val prayerGear: PrayerGear,
    val consumables: List<Consumable>,
) {
    data class KindDefaults(val delayTicks: Int, val attackDelayTicks: Int, val animation: Int, val sound: Int)

    val byItem: Map<String, Consumable> = consumables.associateBy { it.item }
}

object Consumables {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    val SKILLS: Map<String, Int> = mapOf(
        "attack" to Skills.ATTACK, "defence" to Skills.DEFENCE, "strength" to Skills.STRENGTH, "hitpoints" to Skills.HITPOINTS,
        "ranged" to Skills.RANGED, "prayer" to Skills.PRAYER, "magic" to Skills.MAGIC, "cooking" to Skills.COOKING,
        "woodcutting" to Skills.WOODCUTTING, "fletching" to Skills.FLETCHING, "fishing" to Skills.FISHING,
        "firemaking" to Skills.FIREMAKING, "crafting" to Skills.CRAFTING, "smithing" to Skills.SMITHING, "mining" to Skills.MINING,
        "herblore" to Skills.HERBLORE, "agility" to Skills.AGILITY, "thieving" to Skills.THIEVING, "slayer" to Skills.SLAYER,
        "farming" to Skills.FARMING, "runecrafting" to Skills.RUNECRAFTING, "hunter" to Skills.HUNTER, "construction" to Skills.CONSTRUCTION,
    )

    fun load(file: Path): ConsumablesTable = parse(Files.readString(file))

    fun parse(json: String): ConsumablesTable {
        val root: JsonNode = mapper.readTree(json)
        require(root["schemaVersion"]?.asInt() == 1) { "consumables.json: unsupported schemaVersion ${root["schemaVersion"]}" }
        val defaults = root["defaults"]?.fields()?.asSequence()?.associate { (key, node) ->
            Kind.of(key) to mapper.readValue<ConsumablesTable.KindDefaults>(node.toString())
        } ?: emptyMap()
        Kind.values().forEach { require(it in defaults) { "consumables.json: defaults for kind '${it.key}' are missing" } }
        val gearNode = root["prayerGear"] ?: throw IllegalArgumentException("consumables.json: prayerGear is missing")
        val prayerGear = PrayerGear(gearNode["worn"].map { it.asText() }, gearNode["carried"].map { it.asText() })
        val consumables = root["consumables"].map { node -> consumable(node, defaults) }
        return ConsumablesTable(defaults, prayerGear, consumables)
    }

    private fun consumable(node: JsonNode, defaults: Map<Kind, ConsumablesTable.KindDefaults>): Consumable {
        val item = node["item"]?.asText() ?: throw IllegalArgumentException("consumables.json: an entry has no item")
        val kind = Kind.of(node["kind"]?.asText() ?: throw IllegalArgumentException("$item: no kind"))
        val d = defaults.getValue(kind)
        return Consumable(
            item = item,
            kind = kind,
            heal = node["heal"]?.let { heal(item, it) },
            effects = node["effects"]?.map { effect(item, it) } ?: emptyList(),
            replacement = node["replacement"]?.asText(),
            delayTicks = node["delayTicks"]?.asInt() ?: d.delayTicks,
            attackDelayTicks = node["attackDelayTicks"]?.asInt() ?: d.attackDelayTicks,
            animation = node["animation"]?.asInt() ?: d.animation,
            sound = node["sound"]?.asInt() ?: d.sound,
            source = node["source"]?.asText(),
        )
    }

    private fun heal(item: String, node: JsonNode): Heal {
        val overheal = node["overheal"]?.asBoolean() ?: false
        return when {
            node.has("fixed") -> Heal.Fixed(node["fixed"].asInt(), overheal)
            node.has("percentOfBase") -> Heal.PercentOfBase(node["percentOfBase"].asInt(), node["plus"]?.asInt() ?: 0, overheal)
            node.has("brackets") -> Heal.Brackets(node["brackets"].map { b -> Heal.Brackets.Bracket(b[0].asInt(), b[1].asInt(), b[2].asInt()) }, overheal)
            node.has("range") -> Heal.Range(node["range"][0].asInt(), node["range"][1].asInt(), overheal)
            else -> throw IllegalArgumentException("$item: heal needs fixed, percentOfBase, brackets or range")
        }
    }

    private fun effect(item: String, node: JsonNode): Effect {
        fun skills(n: JsonNode): List<Int> {
            val names = if (n.has("skill")) listOf(n["skill"].asText()) else n["skills"]?.map { it.asText() } ?: emptyList()
            require(names.isNotEmpty()) { "$item: an effect names no skill" }
            return names.map { SKILLS[it] ?: throw IllegalArgumentException("$item: unknown skill '$it'") }
        }
        return when {
            node.has("boost") -> node["boost"].let { Effect.Boost(skills(it), it["percentOfBase"]?.asInt() ?: 0, it["plus"]?.asInt() ?: 0) }
            node.has("drain") -> node["drain"].let { Effect.Drain(skills(it), it["percentOfCurrent"]?.asInt() ?: 0, it["plus"]?.asInt() ?: 0) }
            node.has("restore") -> node["restore"].let {
                val target = if (it["skills"]?.isTextual == true && it["skills"].asText() == "all-lowered-except-hitpoints") {
                    Effect.Restore.Target.AllLoweredExceptHitpoints
                } else {
                    Effect.Restore.Target.Skills(skills(it))
                }
                Effect.Restore(target, it["percentOfBase"]?.asInt() ?: 0, it["plus"]?.asInt() ?: 0, it["prayerGearPercent"]?.asInt())
            }
            else -> throw IllegalArgumentException("$item: effect needs boost, drain or restore")
        }
    }
}
