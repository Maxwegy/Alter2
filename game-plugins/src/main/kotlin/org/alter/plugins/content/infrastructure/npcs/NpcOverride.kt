package org.alter.plugins.content.infrastructure.npcs

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.game.model.combat.NpcCombatDef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * One file in `data/cfg/npcs/overrides`. Every field is optional; a field left out keeps the base definition's
 * value, whether that base came from the wiki snapshot + cache or from a hand-written `setCombatDef`.
 */
data class NpcOverride(
    val npc: String? = null,
    val npcs: List<String> = emptyList(),
    val hitpoints: Int? = null,
    val attack: Int? = null,
    val strength: Int? = null,
    val defence: Int? = null,
    val magic: Int? = null,
    val ranged: Int? = null,
    val attackSpeed: Int? = null,
    val respawnTicks: Int? = null,
    val aggressive: Boolean? = null,
) {
    /** The names of the fields this override sets, in declaration order (for `::wikinpc` and the logs). */
    @get:JsonIgnore
    val fields: List<String>
        get() = listOfNotNull(
            hitpoints?.let { "hitpoints" },
            attack?.let { "attack" },
            strength?.let { "strength" },
            defence?.let { "defence" },
            magic?.let { "magic" },
            ranged?.let { "ranged" },
            attackSpeed?.let { "attackSpeed" },
            respawnTicks?.let { "respawnTicks" },
            aggressive?.let { "aggressive" },
        )

    /**
     * [base] with the set fields replaced; everything else (animations, sounds, bonuses, species, immunities,
     * slayer data, loot tables) is kept. `aggressive: false` clears the aggression radius, delay and timer;
     * `aggressive: true` keeps a base that is already aggressive and gives a passive one the defaults
     * (radius [DEFAULT_AGGRO_RADIUS], search delay [DEFAULT_AGGRO_SEARCH_DELAY], timer [DEFAULT_AGGRO_TIMER]).
     * An override that sets nothing returns [base] itself.
     */
    fun applyTo(base: NpcCombatDef): NpcCombatDef {
        if (fields.isEmpty()) return base
        val aggro = when (aggressive) {
            null -> Triple(base.aggressiveRadius, base.aggroTargetDelay, base.aggressiveTimer)
            false -> Triple(0, 0, 0)
            true ->
                if (base.aggressiveRadius > 0) {
                    Triple(base.aggressiveRadius, base.aggroTargetDelay, base.aggressiveTimer)
                } else {
                    Triple(DEFAULT_AGGRO_RADIUS, DEFAULT_AGGRO_SEARCH_DELAY, DEFAULT_AGGRO_TIMER)
                }
        }
        return base.copy(
            hitpoints = hitpoints ?: base.hitpoints,
            attack = attack ?: base.attack,
            strength = strength ?: base.strength,
            defence = defence ?: base.defence,
            magic = magic ?: base.magic,
            ranged = ranged ?: base.ranged,
            attackSpeed = attackSpeed ?: base.attackSpeed,
            respawnDelay = respawnTicks ?: base.respawnDelay,
            aggressiveRadius = aggro.first,
            aggroTargetDelay = aggro.second,
            aggressiveTimer = aggro.third,
        )
    }

    companion object {
        const val DEFAULT_AGGRO_RADIUS = 4
        const val DEFAULT_AGGRO_SEARCH_DELAY = 2
        const val DEFAULT_AGGRO_TIMER = 1000
    }
}

/**
 * Pure helpers for the override files: reading them, and laying them over (and lifting them off) the
 * definitions that hand-written plugins registered. No world, cache or RSCM access, so the tests can drive them.
 */
object NpcOverrides {
    private val yaml = YAMLMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Reads every `.yml`/`.yaml` file in [dir] in name order, keyed by NPC id ([resolve] turns an RSCM name into
     * an id and throws on an unknown one). A file that fails to parse or names an unknown NPC is skipped whole
     * and reported to [onError]. When two files name the same NPC, the later file wins. Does disk IO: call it
     * off the game thread.
     */
    fun load(dir: Path, resolve: (String) -> Int, onError: (Path, Exception) -> Unit): Map<Int, NpcOverride> {
        if (!Files.isDirectory(dir)) return emptyMap()
        val files = Files.list(dir).use { stream -> stream.filter { it.extension in setOf("yml", "yaml") }.sorted().toList() }
        val result = HashMap<Int, NpcOverride>()
        files.forEach { file ->
            try {
                val override = yaml.readValue<NpcOverride>(file.toFile())
                val ids = (override.npcs + listOfNotNull(override.npc)).map(resolve)
                ids.forEach { result[it] = override }
            } catch (e: Exception) {
                onError(file, e)
            }
        }
        return result
    }

    /**
     * Applies [overrides] to every definition in [defs] that is not in [owned] (the wiki-owned ids, whose
     * overrides are applied when they are built). The first time an id is overlaid, its pristine definition is
     * saved in [originals]; the overlay is always computed from that original, so repeated overlays never compound.
     * Returns the ids that were overlaid. An override for an id with no definition at all is ignored.
     */
    fun overlay(
        defs: MutableMap<Int, NpcCombatDef>,
        overrides: Map<Int, NpcOverride>,
        owned: Set<Int>,
        originals: MutableMap<Int, NpcCombatDef>,
    ): Set<Int> {
        val overlaid = HashSet<Int>()
        overrides.forEach { (id, override) ->
            if (id in owned) return@forEach
            val original = originals[id] ?: defs[id] ?: return@forEach
            originals.putIfAbsent(id, original)
            defs[id] = override.applyTo(original)
            overlaid.add(id)
        }
        return overlaid
    }

    /** Puts the pristine definition from [originals] back for every id in [overlaid]. */
    fun restore(
        defs: MutableMap<Int, NpcCombatDef>,
        overlaid: Set<Int>,
        originals: Map<Int, NpcCombatDef>,
    ) {
        overlaid.forEach { id -> originals[id]?.let { defs[id] = it } }
    }

    /** Where an NPC's combat def came from, as `::wikinpc` prints it. */
    fun origin(hasDef: Boolean, wikiOwned: Boolean, override: NpcOverride?): String {
        val suffix = override?.fields?.takeIf { it.isNotEmpty() }?.let { " + override (${it.joinToString(", ")})" }.orEmpty()
        return when {
            !hasDef -> "none (engine default)"
            wikiOwned -> "wiki snapshot + cache$suffix"
            else -> "hand-written plugin$suffix"
        }
    }

    /** The ids whose definition differs (by value) between [before] and [after]; a removed definition counts. */
    fun changed(before: Map<Int, NpcCombatDef>, after: Map<Int, NpcCombatDef>, ids: Set<Int>): Set<Int> =
        ids.filterTo(HashSet()) { before[it] != after[it] }
}
