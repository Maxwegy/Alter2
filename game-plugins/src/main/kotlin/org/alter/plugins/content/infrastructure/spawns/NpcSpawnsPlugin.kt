package org.alter.plugins.content.infrastructure.spawns

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.config.DataPaths
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM
import kotlin.system.exitProcess

/**
 * Spawns every NPC listed in `data/cfg/spawns/npcs/<regionId>.json`. The files are read once, here, while
 * plugins load; the engine spawns the queued NPCs after all services (and so the wiki combat defs) have
 * initialised and before any player can log in. Nothing in the server writes these files.
 *
 * Any invalid entry stops the boot (rule 10): every problem is logged first, then the process exits.
 * A missing directory only warns, so a checkout without spawn data still boots.
 */
class NpcSpawnsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val logger = KotlinLogging.logger {}

    init {
        val dir = DataPaths.default().npcSpawns
        val index = when (val result = NpcSpawnsLoader { name -> runCatching { getRSCM(name) }.getOrNull() }.load(dir)) {
            NpcSpawnsLoader.Result.Missing -> {
                logger.warn { "No NPC spawn data in ${dir.toAbsolutePath().normalize()}; no NPCs spawn from data." }
                emptyMap()
            }
            is NpcSpawnsLoader.Result.Failed -> {
                result.errors.forEach { logger.error { "NPC spawns: $it" } }
                logger.error { "NPC spawns: ${result.errors.size} problem(s) in ${dir.toAbsolutePath().normalize()}; not starting." }
                exitProcess(1)
            }
            is NpcSpawnsLoader.Result.Loaded -> {
                result.spawns.forEach { spawnNpc(it.entry.npc, it.tile, it.entry.walkRadius, it.direction) }
                logger.info { "NPC spawns: ${result.spawns.size} entries from ${result.fileCount} region files (${result.manual} manual, ${result.edits} edited, ${result.wiki} wiki)." }
                result.index
            }
        }
        // Every data spawn by (npc id, spawn tile), for the instance-level spawn tools.
        loadService(NpcSpawnIndexService(index))
    }
}
