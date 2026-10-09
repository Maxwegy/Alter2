package org.alter.plugins.content.infrastructure.npcs

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Gives every NPC in the wiki snapshot a combat definition, unless a plugin already set one by hand.
 *
 * Runs in Service.init: after every plugin's `init {}` (where hand-written `setCombatDef` calls live) and
 * before NPCs spawn. Precedence: hand-written def > override file > cache > snapshot > default.
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

    private var overrides: Map<Int, NpcOverride> = emptyMap()

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        overrides = loadOverrides()
        val stats = register(world)
        logger.info { "NPC combat defs: ${stats.registered} from the wiki snapshot, ${stats.handWritten} hand-written kept, ${overrides.size} overridden." }
    }

    fun spec(npcId: Int): NpcDefSpec? = specs[npcId]

    fun isOwned(npcId: Int) = npcId in owned

    /** Re-registers every wiki-owned definition from the current repository. Call on the game thread. */
    fun reload(world: World): Registration {
        owned.forEach { world.plugins.npcCombatDefs.remove(it) }
        owned.clear()
        specs.clear()
        overrides = loadOverrides()
        return register(world)
    }

    data class Registration(val registered: Int, val handWritten: Int)

    private fun register(world: World): Registration {
        val repository = data.repository
        val defs = world.plugins.npcCombatDefs
        val npcTypes = CacheManager.getNpcs()
        var handWritten = 0
        repository.npcIds().forEach { id ->
            if (defs.containsKey(id)) {
                handWritten++
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
        return Registration(owned.size, handWritten)
    }

    private fun toCombatDef(spec: NpcDefSpec, override: NpcOverride?): NpcCombatDef {
        val aggressive = override?.aggressive ?: spec.aggressive
        return NpcCombatDef.DEFAULT.copy(
            hitpoints = override?.hitpoints ?: spec.hitpoints,
            attack = override?.attack ?: spec.attack,
            strength = override?.strength ?: spec.strength,
            defence = override?.defence ?: spec.defence,
            magic = override?.magic ?: spec.magic,
            ranged = override?.ranged ?: spec.ranged,
            attackSpeed = override?.attackSpeed ?: spec.attackSpeed,
            respawnDelay = override?.respawnTicks ?: spec.respawnTicks ?: NpcCombatDef.DEFAULT.respawnDelay,
            bonuses = spec.bonuses,
            aggressiveRadius = if (aggressive) DEFAULT_AGGRO_RADIUS else 0,
            aggroTargetDelay = if (aggressive) DEFAULT_AGGRO_SEARCH_DELAY else 0,
            aggressiveTimer = if (aggressive) DEFAULT_AGGRO_TIMER else 0,
            immunePoison = spec.immunePoison,
            immuneVenom = spec.immuneVenom,
            immuneCannons = spec.immuneCannon,
            immuneThralls = spec.immuneThrall,
            slayerReq = spec.slayerLevel,
            slayerXp = spec.slayerXp,
            species = spec.attributes.mapNotNull(SPECIES::get).toSet(),
        )
    }

    /** Field-level overrides: the `.yml` files in `data/cfg/npcs/overrides`, keyed by RSCM name. */
    private fun loadOverrides(): Map<Int, NpcOverride> {
        if (!Files.isDirectory(overridesDir)) return emptyMap()
        val files = Files.list(overridesDir).use { stream -> stream.filter { it.extension in setOf("yml", "yaml") }.sorted().toList() }
        val result = HashMap<Int, NpcOverride>()
        files.forEach { file ->
            try {
                val override = yaml.readValue<NpcOverride>(file.toFile())
                (override.npcs + listOfNotNull(override.npc)).forEach { name -> result[RSCM.getRSCM(name)] = override }
            } catch (e: Exception) {
                logger.error(e) { "Skipping NPC override $file" }
            }
        }
        return result
    }

    /** Any field left out keeps the merged cache/wiki value. */
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
    )

    private companion object {
        const val DEFAULT_AGGRO_RADIUS = 4
        const val DEFAULT_AGGRO_SEARCH_DELAY = 2
        const val DEFAULT_AGGRO_TIMER = 1000

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

        val yaml = YAMLMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
