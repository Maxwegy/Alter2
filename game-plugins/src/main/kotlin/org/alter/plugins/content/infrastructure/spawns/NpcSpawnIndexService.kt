package org.alter.plugins.content.infrastructure.spawns

import org.alter.game.model.entity.Npc
import org.alter.game.service.Service

/**
 * The spawn-file entry behind each data-spawned NPC, keyed by (npc id, spawn tile) as the boot loader built
 * it. The live spawn commands look NPCs up here and keep it current when they change one, so a later command
 * on the same NPC finds the edited entry. Game thread only; nothing here touches the disk.
 */
class NpcSpawnIndexService(spawns: Map<NpcSpawnsLoader.Key, NpcSpawnsLoader.Spawn>) : Service {
    private val byKey = HashMap(spawns)

    val size: Int get() = byKey.size

    /** The entry [npc] was spawned from, or null when it did not come from a spawn file. */
    fun find(npc: Npc): NpcSpawnsLoader.Spawn? = byKey[NpcSpawnsLoader.Key(npc.id, npc.spawnTile)]

    /** Replaces [old] with [new] (which may sit on another tile). */
    fun replace(old: NpcSpawnsLoader.Spawn, new: NpcSpawnsLoader.Spawn) {
        byKey.remove(key(old))
        byKey[key(new)] = new
    }

    /** Adds [spawn], for an NPC spawned in game that has no entry yet. */
    fun add(spawn: NpcSpawnsLoader.Spawn) {
        byKey[key(spawn)] = spawn
    }

    fun remove(spawn: NpcSpawnsLoader.Spawn) {
        byKey.remove(key(spawn))
    }

    private fun key(spawn: NpcSpawnsLoader.Spawn) = NpcSpawnsLoader.Key(spawn.npcId, spawn.tile)
}
