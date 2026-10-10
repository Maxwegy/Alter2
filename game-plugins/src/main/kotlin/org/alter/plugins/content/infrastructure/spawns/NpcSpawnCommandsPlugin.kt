package org.alter.plugins.content.infrastructure.spawns

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.data.spawns.NpcSpawnEntry
import org.alter.data.spawns.NpcSpawnSource
import org.alter.data.spawns.SpawnEdit
import org.alter.data.spawns.SpawnPlacement
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.EntityType
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import java.time.Instant

/**
 * Instance-level spawn tools (DEV_POWER). Each acts on the NPC standing on the player's tile (the same lookup as
 * `::removenpc`), changes that live NPC on the game thread and records the change in the runtime outbox
 * `data/run/spawn-edits.jsonl`, which `./gradlew :alter-data:spawnSync -PspawnArgs="--apply-edits"` applies to
 * `data/cfg/spawns/npcs`. The server never writes the region files.
 *
 * - `::spawninfo`: the NPC's spawn entry, its region file and its source, or "not from a spawn file".
 * - `::setwander <n>`: set the walk radius.
 * - `::setdirection <DIR>`: set the facing direction.
 */
class NpcSpawnCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val index by lazy { world.getService(NpcSpawnIndexService::class.java) }
    private val outbox by lazy { world.getService(SpawnEditOutboxService::class.java)?.outbox }

    init {
        onCommand("spawninfo", Privilege.DEV_POWER, description = "Show the spawn file entry of the NPC on your tile") {
            val p = player
            if (!noArgs(p, "spawninfo")) return@onCommand
            val npc = npcOnTile(p) ?: return@onCommand
            val spawn = index?.find(npc) ?: return@onCommand p.message("This NPC is not from a spawn file.")
            describe(p, spawn, npc)
        }

        onCommand("setwander", Privilege.DEV_POWER, description = "Set the walk radius of the NPC on your tile and record it") {
            val p = player
            val radius = when (val parsed = SpawnCommandArgs.walkRadius(p.getCommandArgs())) {
                is SpawnCommandArgs.Parsed.Error -> return@onCommand p.message(parsed.message)
                is SpawnCommandArgs.Parsed.Ok -> parsed.value
            }
            val (npc, spawn) = spawnOnTile(p) ?: return@onCommand
            npc.walkRadius = radius
            edit(spawn, spawn.entry.copy(walkRadius = radius), spawn.direction)
            p.message("${spawn.entry.npc}: walk radius ${spawn.entry.walkRadius} -> $radius (recorded).")
        }

        onCommand("setdirection", Privilege.DEV_POWER, description = "Set the facing direction of the NPC on your tile and record it") {
            val p = player
            val direction = when (val parsed = SpawnCommandArgs.direction(p.getCommandArgs())) {
                is SpawnCommandArgs.Parsed.Error -> return@onCommand p.message(parsed.message)
                is SpawnCommandArgs.Parsed.Ok -> parsed.value
            }
            val (npc, spawn) = spawnOnTile(p) ?: return@onCommand
            npc.lastFacingDirection = direction
            npc.faceTile(Tile(npc.tile.x + direction.getDeltaX(), npc.tile.z + direction.getDeltaZ(), npc.tile.height))
            edit(spawn, spawn.entry.copy(direction = direction.name), direction)
            p.message("${spawn.entry.npc}: direction ${spawn.direction.name} -> ${direction.name} (recorded).")
        }
    }

    private fun noArgs(p: Player, command: String): Boolean = when (val parsed = SpawnCommandArgs.none(command, p.getCommandArgs())) {
        is SpawnCommandArgs.Parsed.Error -> false.also { p.message(parsed.message) }
        is SpawnCommandArgs.Parsed.Ok -> true
    }

    private fun npcOnTile(p: Player): Npc? {
        val chunk = world.chunks.getOrCreate(p.tile)
        return chunk.getEntities<Npc>(p.tile, EntityType.NPC).firstOrNull() ?: null.also { p.message("No NPC found in tile.") }
    }

    /** The NPC on [p]'s tile and its spawn entry, or null (with a message) when either is missing. */
    private fun spawnOnTile(p: Player): Pair<Npc, NpcSpawnsLoader.Spawn>? {
        val npc = npcOnTile(p) ?: return null
        val spawn = index?.find(npc) ?: return null.also { p.message("This NPC is not from a spawn file; nothing to record.") }
        if (outbox == null) return null.also { p.message("The spawn edit outbox is not running; nothing changed.") }
        return npc to spawn
    }

    /** Keeps the index current and queues the outbox line (written on the IO scope, never here). */
    private fun edit(spawn: NpcSpawnsLoader.Spawn, entry: NpcSpawnEntry, direction: Direction) {
        index!!.replace(spawn, NpcSpawnsLoader.Spawn(entry, spawn.npcId, direction))
        outbox!!.record(SpawnEdit.of(Instant.now().toString(), spawn.entry, SpawnPlacement.of(entry)))
    }

    private fun describe(p: Player, spawn: NpcSpawnsLoader.Spawn, npc: Npc) {
        val e = spawn.entry
        p.message("${e.npc} (id ${spawn.npcId}) at ${e.x}, ${e.z}, ${e.height}: walk radius ${e.walkRadius}, facing ${e.direction ?: "${spawn.direction.name} (default)"}.")
        p.message("File: data/cfg/spawns/npcs/${e.regionId}.json")
        when (val s = e.source) {
            NpcSpawnSource.Manual -> p.message("Source: manual.")
            is NpcSpawnSource.Edit -> p.message("Source: edited ${s.at}${s.page?.let { ", from $it" } ?: ""}.")
            is NpcSpawnSource.Wiki -> p.message("Source: wiki, ${s.page}")
        }
        e.note?.let { p.message("Note: $it") }
        if (npc.walkRadius != e.walkRadius) p.message("Live walk radius: ${npc.walkRadius}.")
    }
}
