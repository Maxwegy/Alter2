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

    /** Restores [restorePercent] of run energy; with [staminaTicks] also starts the stamina effect (70% less depletion). */
    data class RunEnergy(val restorePercent: Int, val staminaTicks: Int?) : Effect

    /** Cures poison when [cure] and grants poison immunity for [immunityTicks]. */
    data class Antipoison(val cure: Boolean, val immunityTicks: Int) : Effect

    /** Cures venom when [cure] and grants venom immunity for [immunityTicks] (JSON key `antivenom`). */
    data class Antivenom(val cure: Boolean, val immunityTicks: Int) : Effect

    /** Dragonfire protection for [ticks]: PARTIAL is the antifire potion (full only with a shield), FULL the super antifire. */
    data class Antifire(val tier: AntifireTier, val ticks: Int) : Effect
}

enum class AntifireTier(val key: String) {
    PARTIAL("partial"),
    FULL("full"),
    ;

    companion object {
        fun of(key: String): AntifireTier = values().firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown antifire tier '$key'")
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

/** Chat lines for effects that end on a timer; data, so the plugins carry no strings either. */
/** [staminaExpired] is null until a sourced text exists (TODO in the file); then nothing is sent. */
data class Messages(val antifireWarning: String, val antifireExpired: String, val staminaExpired: String?)

data class ConsumablesTable(
    val defaults: Map<Kind, KindDefaults>,
    val prayerGear: PrayerGear,
    val consumables: List<Consumable>,
    val messages: Messages = Messages("", "", ""),
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
        val m = root["messages"] ?: throw IllegalArgumentException("consumables.json: messages is missing")
        fun message(key: String) = m[key]?.asText()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("consumables.json: messages.$key is missing")
        val messages = Messages(message("antifireWarning"), message("antifireExpired"), m["staminaExpired"]?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() })
        return ConsumablesTable(defaults, prayerGear, consumables, messages)
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
            node.has("runEnergy") -> node["runEnergy"].let {
                val percent = it["restorePercent"]?.asInt() ?: throw IllegalArgumentException("$item: runEnergy needs restorePercent")
                require(percent in 1..100) { "$item: runEnergy.restorePercent must be 1..100" }
                val stamina = it["staminaTicks"]?.asInt()
                require(stamina == null || stamina > 0) { "$item: runEnergy.staminaTicks must be positive" }
                Effect.RunEnergy(percent, stamina)
            }
            node.has("antipoison") -> node["antipoison"].let {
                val ticks = it["immunityTicks"]?.asInt() ?: 0
                require(ticks >= 0) { "$item: antipoison.immunityTicks must not be negative" }
                Effect.Antipoison(it["cure"]?.asBoolean() ?: true, ticks)
            }
            node.has("antivenom") -> node["antivenom"].let {
                val ticks = it["immunityTicks"]?.asInt() ?: 0
                require(ticks >= 0) { "$item: antivenom.immunityTicks must not be negative" }
                Effect.Antivenom(it["cure"]?.asBoolean() ?: true, ticks)
            }
            node.has("antifire") -> node["antifire"].let {
                val ticks = it["ticks"]?.asInt() ?: throw IllegalArgumentException("$item: antifire needs ticks")
                require(ticks > 0) { "$item: antifire.ticks must be positive" }
                Effect.Antifire(AntifireTier.of(it["tier"]?.asText() ?: throw IllegalArgumentException("$item: antifire needs tier")), ticks)
            }
            else -> throw IllegalArgumentException("$item: effect needs boost, drain, restore, runEnergy, antipoison, antivenom or antifire")
        }
    }
}
