package org.alter.plugins.content.infrastructure.spawns

import org.alter.data.spawns.NpcSpawnEntry
import org.alter.data.spawns.NpcSpawnFiles
import org.alter.data.spawns.NpcSpawnSource
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import java.nio.file.Path

/**
 * Reads `data/cfg/spawns/npcs` and checks everything the engine needs before a single NPC is queued: the file
 * format (via [NpcSpawnFiles]), that every name resolves to an NPC id, that every direction is a compass
 * [Direction], and that no two entries put the same NPC id on the same tile (an alias and its canonical name
 * would otherwise slip past the name-based duplicate check). All problems are collected, never thrown.
 *
 * [resolveNpc] maps an RSCM name to its id, or null when the name is unknown.
 */
class NpcSpawnsLoader(private val resolveNpc: (String) -> Int?) {
    data class Spawn(val entry: NpcSpawnEntry, val npcId: Int, val direction: Direction) {
        val tile: Tile get() = Tile(entry.x, entry.z, entry.height)
    }

    /** The key the instance-level spawn tools use to find an NPC's entry: its id and its spawn tile. */
    data class Key(val npcId: Int, val tile: Tile)

    sealed interface Result {
        /** The spawn directory does not exist. */
        data object Missing : Result

        data class Failed(val errors: List<String>) : Result

        data class Loaded(val spawns: List<Spawn>, val fileCount: Int) : Result {
            val manual: Int get() = spawns.count { it.entry.source is NpcSpawnSource.Manual }
            val wiki: Int get() = spawns.size - manual
            val index: Map<Key, Spawn> get() = spawns.associateBy { Key(it.npcId, it.tile) }
        }
    }

    fun load(dir: Path): Result {
        val read = when (val result = NpcSpawnFiles.readAll(dir)) {
            NpcSpawnFiles.ReadResult.Missing -> return Result.Missing
            is NpcSpawnFiles.ReadResult.Read -> result
        }
        val errors = read.errors.toMutableList()
        val spawns = read.files.flatMap { file ->
            file.spawns.mapIndexedNotNull { i, entry ->
                val where = "${file.regionId}.json: spawns[$i]"
                val id = resolveNpc(entry.npc)
                if (id == null) errors += "$where: ${entry.npc} is not in data/cfg/rscm/npc.rscm"
                val direction = when (val name = entry.direction) {
                    null -> DEFAULT_DIRECTION
                    else -> COMPASS[name].also { if (it == null) errors += "$where: direction '$name' is not one of ${COMPASS.keys.joinToString()}" }
                }
                if (id == null || direction == null) null else Spawn(entry, id, direction)
            }
        }
        spawns.groupBy { Key(it.npcId, it.tile) }.filterValues { it.size > 1 }.forEach { (key, dupes) ->
            errors += "npc id ${key.npcId} spawns twice at (${key.tile.x}, ${key.tile.z}, ${key.tile.height}): ${dupes.joinToString { it.entry.npc }}"
        }
        if (errors.isNotEmpty()) return Result.Failed(errors)
        return Result.Loaded(spawns, read.files.size)
    }

    companion object {
        /** What `spawnNpc` uses when no direction is given. */
        val DEFAULT_DIRECTION = Direction.SOUTH

        private val COMPASS: Map<String, Direction> = Direction.values().filter { it != Direction.NONE }.associateBy { it.name }
    }
}
