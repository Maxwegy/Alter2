package org.alter.plugins.content.infrastructure.npcs

import dev.openrune.cache.CacheManager
import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.alter.api.NpcSpecies
import org.alter.data.npcs.NpcCacheStats
import org.alter.data.npcs.NpcDefSpec
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.service.Service
import org.alter.plugins.content.infrastructure.GameDataService
import org.alter.rscm.RSCM
import java.nio.file.Path

/**
 * Gives every NPC in the wiki snapshot a combat definition, unless a plugin already set one by hand, and lays the
 * override files in `data/cfg/npcs/overrides` over both kinds.
 *
 * Runs in Service.init: after every plugin's `init {}` (where hand-written `setCombatDef` calls live) and
 * before NPCs spawn. Precedence, per field: override file > hand-written def > cache > snapshot > default. A
 * hand-written def keeps the wiki stats out wholesale; an override changes only the fields it sets and keeps the
 * plugin's animations, sounds, bonuses, species and immunities.
 *
 * The pristine hand-written defs are kept in [handWritten], so a reload lifts the old overlay off before laying
 * the new one on: deleting an override file and reloading restores the plugin's def, and reloads never compound.
 */
class NpcDataService(
    private val data: GameDataService,
    private val overridesDir: Path,
) : Service {
    private val logger = KotlinLogging.logger {}

    /** NPC ids whose definition this service registered, so a hot reload can replace exactly those. */
    private val owned = IntOpenHashSet()

    /** Specs by NPC id, for `::wikinpc`. */
    private val specs = HashMap<Int, NpcDefSpec>()

    /** The pristine hand-written defs of every id an override has ever been laid over. */
    private val handWritten = HashMap<Int, NpcCombatDef>()

    /** Hand-written ids that currently carry an override. */
    private var overlaid: Set<Int> = emptySet()

    private var overrides: Map<Int, NpcOverride> = emptyMap()

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        overrides = loadOverrides()
        val stats = register(world)
        logger.info {
            "NPC combat defs: ${stats.registered} from the wiki snapshot, ${stats.handWritten} hand-written kept, " +
                "${overrides.size} overridden (${stats.overlaid} of them hand-written)."
        }
    }

    fun spec(npcId: Int): NpcDefSpec? = specs[npcId]

    fun isOwned(npcId: Int) = npcId in owned

    /** The override that currently applies to [npcId], or null. Game thread. */
    fun overrideFor(npcId: Int): NpcOverride? = overrides[npcId]?.takeIf { npcId in owned || npcId in overlaid }

    /** Where [npcId]'s combat def came from, as `::wikinpc` prints it. Game thread. */
    fun origin(world: World, npcId: Int): String =
        NpcOverrides.origin(world.plugins.npcCombatDefs.containsKey(npcId), npcId in owned, overrideFor(npcId))

    /**
     * Lifts the current overlay off, re-registers every wiki-owned definition from the current repository and
     * lays [overrides] over wiki and hand-written defs again. Call on the game thread; read [overrides] off it
     * with [loadOverrides]. Live NPCs keep their old def until [applyToLive] is called with [Registration.changed].
     */
    fun reload(world: World, overrides: Map<Int, NpcOverride> = loadOverrides()): Registration {
        val defs = world.plugins.npcCombatDefs
        val tracked = HashSet<Int>().apply {
            addAll(owned)
            addAll(overlaid)
        }
        val before = tracked.mapNotNull { id -> defs[id]?.let { id to it } }.toMap()
        NpcOverrides.restore(defs, overlaid, handWritten)
        overlaid = emptySet()
        owned.forEach { defs.remove(it) }
        owned.clear()
        specs.clear()
        this.overrides = overrides
        val stats = register(world)
        tracked.addAll(owned)
        tracked.addAll(overlaid)
        return stats.copy(changed = NpcOverrides.changed(before, defs, tracked))
    }

    /**
     * Re-applies `world.setNpcDefaults` to every live NPC whose id is in [ids]: the new def, its levels, and full
     * hitpoints. Dead NPCs are skipped; they pick the def up when they respawn. Game thread. Returns how many.
     */
    fun applyToLive(world: World, ids: Set<Int>): Int {
        if (ids.isEmpty()) return 0
        var updated = 0
        world.npcs.forEach { npc ->
            if (npc.id in ids && npc.isAlive()) {
                world.setNpcDefaults(npc)
                updated++
            }
        }
        return updated
    }

    /**
     * What a register or reload did: wiki-owned defs, hand-written defs kept, how many of those carry an override,
     * and (after a reload) the ids whose def changed by value.
     */
    data class Registration(
        val registered: Int,
        val handWritten: Int,
        val overlaid: Int = 0,
        val changed: Set<Int> = emptySet(),
    )

    private fun register(world: World): Registration {
        val repository = data.repository
        val defs = world.plugins.npcCombatDefs
        val npcTypes = CacheManager.getNpcs()
        var handWrittenKept = 0
        repository.npcIds().forEach { id ->
            if (defs.containsKey(id)) {
                handWrittenKept++
                return@forEach
            }
            val entry = repository.npcStats(id) ?: return@forEach
            val cacheStats = npcTypes[id]?.let { type ->
                val params = type.params.orEmpty().mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()
                NpcCacheStats.of(type.attack, type.defence, type.strength, type.hitpoints, type.ranged, type.magic, params)
            }
            val spec = NpcDefSpec.merge(entry, cacheStats)
            specs[id] = spec
            defs.put(id, toCombatDef(spec, overrides[id]))
            owned.add(id)
        }
        overlaid = NpcOverrides.overlay(defs, overrides, owned, handWritten)
        return Registration(owned.size, handWrittenKept, overlaid.size)
    }

    private fun toCombatDef(spec: NpcDefSpec, override: NpcOverride?): NpcCombatDef {
        val base = baseDef(spec)
        return override?.applyTo(base) ?: base
    }

    private fun baseDef(spec: NpcDefSpec): NpcCombatDef =
        NpcCombatDef.DEFAULT.copy(
            hitpoints = spec.hitpoints,
            attack = spec.attack,
            strength = spec.strength,
            defence = spec.defence,
            magic = spec.magic,
            ranged = spec.ranged,
            attackSpeed = spec.attackSpeed,
            respawnDelay = spec.respawnTicks ?: NpcCombatDef.DEFAULT.respawnDelay,
            bonuses = spec.bonuses,
            aggressiveRadius = if (spec.aggressive) NpcOverride.DEFAULT_AGGRO_RADIUS else 0,
            aggroTargetDelay = if (spec.aggressive) NpcOverride.DEFAULT_AGGRO_SEARCH_DELAY else 0,
            aggressiveTimer = if (spec.aggressive) NpcOverride.DEFAULT_AGGRO_TIMER else 0,
            immunePoison = spec.immunePoison,
            immuneVenom = spec.immuneVenom,
            immuneCannons = spec.immuneCannon,
            immuneThralls = spec.immuneThrall,
            slayerReq = spec.slayerLevel,
            slayerXp = spec.slayerXp,
            species = spec.attributes.mapNotNull(SPECIES::get).toSet(),
        )

    /**
     * Field-level overrides: the `.yml` files in `data/cfg/npcs/overrides`, keyed by NPC id (resolved from RSCM
     * names). Disk IO: `::reloadnpcs`, `::wikisync` and `POST /wiki/reload` call it off the game thread and pass
     * the result to [reload]. A bad file is logged, skipped, and its name added to [skipped] when given.
     */
    fun loadOverrides(skipped: MutableList<String>? = null): Map<Int, NpcOverride> =
        NpcOverrides.load(overridesDir, { RSCM.getRSCM(it) }) { file, e ->
            logger.error(e) { "Skipping NPC override $file" }
            skipped?.add(file.fileName.toString())
        }

    private companion object {
        val SPECIES = mapOf(
            "demon" to NpcSpecies.DEMON,
            "dragon" to NpcSpecies.DRACONIC,
            "fiery" to NpcSpecies.FIERY,
            "golem" to NpcSpecies.GOLEM,
            "kalphite" to NpcSpecies.KALPHITE,
            "penance" to NpcSpecies.PENANCE,
            "rat" to NpcSpecies.RAT,
            "shade" to NpcSpecies.SHADE,
            "spectral" to NpcSpecies.SPECTRAL,
            "undead" to NpcSpecies.UNDEAD,
            "vampyre" to NpcSpecies.VAMPYRE,
            "xerician" to NpcSpecies.XERICIAN,
        )
    }
}
