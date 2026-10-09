package org.alter.plugins.content.infrastructure.npcs

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.data.missing.Location
import org.alter.data.missing.MissingContentEvent
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.infrastructure.GameDataService
import org.alter.plugins.content.infrastructure.missingcontent.MissingContentService
import org.alter.rscm.RSCM

/**
 * Reports attackable NPCs that spawn without any combat stats (a data gap), and adds `::wikinpc` to inspect
 * where an NPC's stats came from.
 */
class NpcDataPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val missing by lazy { world.getService(MissingContentService::class.java) }
    private val npcData by lazy { world.getService(NpcDataService::class.java) }
    private val gameData by lazy { world.getService(GameDataService::class.java) }

    init {
        onGlobalNpcSpawn {
            val npc = npc
            if (npc.combatDef === NpcCombatDef.DEFAULT && npc.def.isAttackable()) {
                missing?.record(
                    MissingContentEvent(
                        type = "NPC_NO_STATS",
                        id = npc.id,
                        name = npc.def.name,
                        location = Location(npc.tile.x, npc.tile.z, npc.tile.height),
                    ),
                )
            }
        }

        onCommand("wikinpc", Privilege.DEV_POWER, description = "Show an NPC's combat stats and where they came from") {
            val arg = player.getCommandArgs().firstOrNull() ?: return@onCommand player.message("Usage: ::wikinpc <id | npc.rscm_name>")
            val id = arg.toIntOrNull() ?: runCatching { RSCM.getRSCM(arg) }.getOrNull()
                ?: return@onCommand player.message("Unknown NPC: $arg")
            val def = world.plugins.npcCombatDefs.get(id)
            val origin = when {
                def == null -> "none (engine default)"
                npcData?.isOwned(id) == true -> "wiki snapshot + cache"
                else -> "hand-written plugin"
            }
            player.message("NPC $id: combat def from $origin.")
            def?.let {
                player.message("HP ${it.hitpoints}, att ${it.attack}, str ${it.strength}, def ${it.defence}, mag ${it.magic}, rng ${it.ranged}, speed ${it.attackSpeed}, respawn ${it.respawnDelay}")
            }
            npcData?.spec(id)?.let { spec -> player.message("Sources: ${spec.sources.entries.joinToString { (field, source) -> "$field=$source" }}") }
            gameData?.repository?.npcStats(id)?.let { entry ->
                player.message("Wiki: ${entry.source}, max hit ${entry.maxHits.joinToString()}, styles ${entry.attackStyles.joinToString()}")
            }
        }
    }
}
