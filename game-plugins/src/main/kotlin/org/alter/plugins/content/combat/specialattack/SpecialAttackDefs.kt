package org.alter.plugins.content.combat.specialattack

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.alter.plugins.content.items.consumables.Consumables
import java.nio.file.Files
import java.nio.file.Path

/**
 * The special attack data model: `data/cfg/combat/special_attacks.json` parsed into typed definitions. Every
 * number and chat line comes from the file (each entry cites an `oldschool.runescape.wiki` page); nothing is
 * hard-coded here. Item names are RSCM names resolved by [SpecialAttacksService] at boot, so this file and
 * [SpecialRules] can be tested without a cache or a world.
 */

/** When a special fires. */
sealed interface Trigger {
    /** The bar arms the next attack (most weapons). */
    object NextAttack : Trigger

    /** Clicking the bar fires at once, without a target (self boosts such as the dragon pickaxe). */
    object OnBarClick : Trigger

    /** Clicking the bar fires at once at the last target if it was attacked within [homingTicks] and is adjacent; otherwise like [NextAttack]. */
    data class OnBarClickNearTarget(val homingTicks: Int) : Trigger
}

/** When a damage-dependent effect applies: on a successful hit, only when damage was dealt, or always. */
enum class EffectCondition(val key: String) {
    HIT("hit"),
    DAMAGE("damage"),
    ALWAYS("always"),
    ;

    companion object {
        fun of(key: String): EffectCondition = values().firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown effect condition '$key'")
    }
}

/**
 * One hit of a special. [damage] multipliers are applied one after another with a floor after each;
 * [damageBonusPerMissingPrayerPoint] is a percent per missing Prayer point (the bludgeon).
 */
data class HitSpec(
    val accuracy: Double = 1.0,
    val damage: List<Double> = emptyList(),
    val npcDelayExtra: Int = 0,
    val onlyIfTargetLargerThan1x1: Boolean = false,
    val damageBonusPerMissingPrayerPoint: Double = 0.0,
)

/** Skill names are the keys of [Consumables.SKILLS]; the plugin maps them to player or NPC skill ids. */
sealed interface SpecialEffect {
    val on: EffectCondition

    data class HealSelf(val hitpointsPercentOfDamage: Int, val prayerPercentOfDamage: Int, val minHitpoints: Int, val minPrayer: Int, override val on: EffectCondition) : SpecialEffect

    data class DrainTargetSkill(val skill: String, val percentOfCurrent: Int, override val on: EffectCondition) : SpecialEffect

    /** Levels equal to the damage, spent along [order]; a skill is drained only once the one before it is at 0. */
    data class DrainTargetByDamage(val order: List<String>, override val on: EffectCondition) : SpecialEffect

    data class FreezeTarget(val ticks: Int, override val on: EffectCondition) : SpecialEffect

    /** Each skill loses `plus + floor(base * percent / 100)`, with [demonPercentOfBase] against demons. */
    data class DrainTargetSkills(val skills: List<String>, val percentOfBase: Int, val demonPercentOfBase: Int, val plus: Int, override val on: EffectCondition) : SpecialEffect

    data class TransferRunEnergy(val percent: Int, val pvpOnly: Boolean, val targetMessage: String?, override val on: EffectCondition) : SpecialEffect

    data class ExtraMagicHit(val min: Int, val max: Int, val magicXpPerDamage: Double, override val on: EffectCondition) : SpecialEffect

    /** Applied when the special fires, not after a hit. */
    data class BoostSelf(val skill: String, val plus: Int) : SpecialEffect {
        override val on: EffectCondition get() = EffectCondition.ALWAYS
    }
}

data class GraphicSpec(val id: Int, val height: Int = 0)

data class SoundSpec(val id: Int, val delay: Int = 0, val source: String? = null, val verify: Boolean = false)

data class SpecialDef(
    val items: List<String>,
    val name: String?,
    val energy: Int,
    val trigger: Trigger,
    val combat: String?,
    val hits: List<HitSpec>,
    val sharedRoll: Boolean,
    val effects: List<SpecialEffect>,
    val animation: Int?,
    val graphic: GraphicSpec?,
    val sound: SoundSpec?,
    val forceChat: String?,
    val noAttackDelay: Boolean,
    val source: String?,
    val notes: List<String>,
    val todo: String?,
) {
    /** A non-empty todo means the entry is documented but not loaded. */
    val loaded: Boolean get() = todo.isNullOrBlank()
}

data class EnergySettings(val max: Int, val regenPercent: Int, val regenIntervalTicks: Int, val resetTimerOnLogin: Boolean)

/** [insufficientEnergy] is null while no sourced text exists; then nothing is sent. */
data class SpecialMessages(val insufficientEnergy: String?)

data class SpecialAttacksTable(
    val energy: EnergySettings,
    val messages: SpecialMessages,
    val specials: List<SpecialDef>,
    val todo: List<String> = emptyList(),
) {
    val loaded: List<SpecialDef> get() = specials.filter { it.loaded }
    val skipped: List<SpecialDef> get() = specials.filterNot { it.loaded }

    /** Item name to its loaded special; TODO entries are left out, so those weapons have no special. */
    val byItem: Map<String, SpecialDef> = loaded.flatMap { def -> def.items.map { it to def } }.toMap()

    companion object {
        val EMPTY = SpecialAttacksTable(EnergySettings(100, 10, 50, true), SpecialMessages(null), emptyList())
    }
}

object SpecialAttackDefs {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(file: Path): SpecialAttacksTable = parse(Files.readString(file))

    fun parse(json: String): SpecialAttacksTable {
        val root: JsonNode = mapper.readTree(json)
        require(root["schemaVersion"]?.asInt() == 1) { "special_attacks.json: unsupported schemaVersion ${root["schemaVersion"]}" }
        val e = root["energy"] ?: throw IllegalArgumentException("special_attacks.json: energy is missing")
        fun int(n: JsonNode, key: String) = n[key]?.takeIf { it.isNumber }?.asInt() ?: throw IllegalArgumentException("special_attacks.json: energy.$key is missing")
        val energy = EnergySettings(int(e, "max"), int(e, "regenPercent"), int(e, "regenIntervalTicks"), e["resetTimerOnLogin"]?.asBoolean() ?: true)
        require(energy.max > 0 && energy.regenPercent > 0 && energy.regenIntervalTicks > 0) { "special_attacks.json: energy values must be positive" }
        val m = root["messages"] ?: throw IllegalArgumentException("special_attacks.json: messages is missing")
        val messages = SpecialMessages(text(m["insufficientEnergy"]))
        val specials = root["specials"]?.map { special(it) } ?: throw IllegalArgumentException("special_attacks.json: specials is missing")
        val todo = root["todo"]?.map { it.asText() } ?: emptyList()
        return SpecialAttacksTable(energy, messages, specials, todo)
    }

    private fun text(n: JsonNode?): String? = n?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private fun special(node: JsonNode): SpecialDef {
        val items = node["items"]?.map { it.asText() } ?: emptyList()
        require(items.isNotEmpty()) { "special_attacks.json: an entry has no items" }
        val id = items.first()
        val energy = node["energy"]?.asInt() ?: throw IllegalArgumentException("$id: energy is missing")
        require(energy in 5..100) { "$id: energy $energy is outside 5..100" }
        val todo = text(node["todo"])
        val def = SpecialDef(
            items = items,
            name = text(node["name"]),
            energy = energy,
            trigger = trigger(id, node["trigger"]),
            combat = text(node["combat"]),
            hits = node["hits"]?.map { hit(id, it) } ?: emptyList(),
            sharedRoll = node["sharedRoll"]?.asBoolean() ?: false,
            effects = node["effects"]?.map { effect(id, it) } ?: emptyList(),
            animation = node["animation"]?.takeUnless { it.isNull }?.asInt(),
            graphic = node["graphic"]?.takeUnless { it.isNull }?.let { GraphicSpec(it["id"].asInt(), it["height"]?.asInt() ?: 0) },
            sound = node["sound"]?.takeUnless { it.isNull }?.let { SoundSpec(it["id"].asInt(), it["delay"]?.asInt() ?: 0, text(it["source"]), it["verify"]?.asBoolean() ?: false) },
            forceChat = text(node["forceChat"]),
            noAttackDelay = node["noAttackDelay"]?.asBoolean() ?: false,
            source = text(node["source"]),
            notes = node["notes"]?.map { it.asText() } ?: emptyList(),
            todo = todo,
        )
        if (def.loaded) {
            require(def.hits.isNotEmpty() || def.effects.isNotEmpty()) { "$id: a loaded special needs hits or effects" }
            require(def.animation != null) { "$id: a loaded special needs an animation" }
            require(def.hits.isEmpty() || def.combat == "melee") { "$id: only melee hits are supported (combat '${def.combat}')" }
            require(def.hits.isNotEmpty() || def.trigger == Trigger.OnBarClick) { "$id: a special without hits must fire on the bar click" }
        }
        return def
    }

    private fun trigger(id: String, node: JsonNode?): Trigger {
        if (node == null || node.isNull) return Trigger.NextAttack
        val type = if (node.isTextual) node.asText() else node["type"]?.asText() ?: throw IllegalArgumentException("$id: trigger has no type")
        return when (type) {
            "next-attack" -> Trigger.NextAttack
            "bar-click" -> Trigger.OnBarClick
            "bar-click-near-target" -> {
                val ticks = node["homingTicks"]?.asInt() ?: throw IllegalArgumentException("$id: bar-click-near-target needs homingTicks")
                require(ticks > 0) { "$id: homingTicks must be positive" }
                Trigger.OnBarClickNearTarget(ticks)
            }
            else -> throw IllegalArgumentException("$id: unknown trigger '$type'")
        }
    }

    private fun hit(id: String, node: JsonNode): HitSpec {
        val spec = HitSpec(
            accuracy = node["accuracy"]?.asDouble() ?: 1.0,
            damage = node["damage"]?.map { it.asDouble() } ?: emptyList(),
            npcDelayExtra = node["npcDelayExtra"]?.asInt() ?: 0,
            onlyIfTargetLargerThan1x1 = node["onlyIfTargetLargerThan1x1"]?.asBoolean() ?: false,
            damageBonusPerMissingPrayerPoint = node["damageBonusPerMissingPrayerPoint"]?.asDouble() ?: 0.0,
        )
        require(spec.accuracy > 0 && spec.damage.all { it > 0 } && spec.npcDelayExtra >= 0 && spec.damageBonusPerMissingPrayerPoint >= 0) { "$id: a hit has a bad multiplier" }
        return spec
    }

    private fun skill(id: String, name: String?): String {
        require(name != null && name in Consumables.SKILLS) { "$id: unknown skill '$name'" }
        return name
    }

    private fun on(node: JsonNode, default: EffectCondition) = node["on"]?.asText()?.let { EffectCondition.of(it) } ?: default

    private fun effect(id: String, node: JsonNode): SpecialEffect {
        fun need(n: JsonNode, key: String) = n[key]?.takeUnless { it.isNull } ?: throw IllegalArgumentException("$id: effect needs $key")
        return when {
            node.has("healSelf") -> node["healSelf"].let {
                SpecialEffect.HealSelf(need(it, "hitpointsPercentOfDamage").asInt(), need(it, "prayerPercentOfDamage").asInt(), it["minHitpoints"]?.asInt() ?: 0, it["minPrayer"]?.asInt() ?: 0, on(it, EffectCondition.HIT))
            }
            node.has("drainTargetSkill") -> node["drainTargetSkill"].let {
                SpecialEffect.DrainTargetSkill(skill(id, need(it, "skill").asText()), need(it, "percentOfCurrent").asInt(), on(it, EffectCondition.DAMAGE))
            }
            node.has("drainTargetByDamage") -> node["drainTargetByDamage"].let {
                val order = need(it, "order").map { s -> skill(id, s.asText()) }
                require(order.isNotEmpty()) { "$id: drainTargetByDamage needs an order" }
                SpecialEffect.DrainTargetByDamage(order, on(it, EffectCondition.DAMAGE))
            }
            node.has("freezeTarget") -> node["freezeTarget"].let {
                val ticks = need(it, "ticks").asInt()
                require(ticks > 0) { "$id: freezeTarget.ticks must be positive" }
                SpecialEffect.FreezeTarget(ticks, on(it, EffectCondition.HIT))
            }
            node.has("drainTargetSkills") -> node["drainTargetSkills"].let {
                val skills = need(it, "skills").map { s -> skill(id, s.asText()) }
                val percent = need(it, "percentOfBase").asInt()
                SpecialEffect.DrainTargetSkills(skills, percent, it["demonPercentOfBase"]?.asInt() ?: percent, it["plus"]?.asInt() ?: 0, on(it, EffectCondition.HIT))
            }
            node.has("transferRunEnergy") -> node["transferRunEnergy"].let {
                val percent = need(it, "percent").asInt()
                require(percent in 1..100) { "$id: transferRunEnergy.percent must be 1..100" }
                SpecialEffect.TransferRunEnergy(percent, it["pvpOnly"]?.asBoolean() ?: false, text(it["targetMessage"]), on(it, EffectCondition.HIT))
            }
            node.has("extraMagicHit") -> node["extraMagicHit"].let {
                val min = need(it, "min").asInt()
                val max = need(it, "max").asInt()
                require(min in 0..max) { "$id: extraMagicHit needs 0 <= min <= max" }
                SpecialEffect.ExtraMagicHit(min, max, it["magicXpPerDamage"]?.asDouble() ?: 0.0, on(it, EffectCondition.HIT))
            }
            node.has("boostSelf") -> node["boostSelf"].let {
                val plus = need(it, "plus").asInt()
                require(plus > 0) { "$id: boostSelf.plus must be positive" }
                SpecialEffect.BoostSelf(skill(id, need(it, "skill").asText()), plus)
            }
            else -> throw IllegalArgumentException("$id: unknown effect ${node.fieldNames().asSequence().toList()}")
        }
    }
}
