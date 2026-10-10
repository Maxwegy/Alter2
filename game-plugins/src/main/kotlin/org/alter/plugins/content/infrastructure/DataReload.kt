package org.alter.plugins.content.infrastructure

import org.alter.data.snapshot.GameDataRepository
import org.alter.game.model.World
import org.alter.plugins.content.infrastructure.drops.DropDataService
import org.alter.plugins.content.infrastructure.items.ItemStatsService
import org.alter.plugins.content.infrastructure.npcs.NpcDataService
import org.alter.plugins.content.infrastructure.npcs.NpcOverride

/** Swaps in a new wiki snapshot everywhere it is used. Game thread only. */
object DataReload {
    /** [npcDefs] wiki-owned NPC defs registered, [npcDefsChanged] defs that changed by value, [liveNpcs] live NPCs updated. */
    data class Result(val npcDefs: Int, val npcDefsChanged: Int, val liveNpcs: Int)

    /**
     * [npcOverrides] are the NPC override files, read off the game thread with [NpcDataService.loadOverrides];
     * null re-reads them here (disk IO on the calling thread). NPCs alive now whose def changed get it at once.
     */
    fun apply(world: World, repository: GameDataRepository, npcOverrides: Map<Int, NpcOverride>? = null): Result {
        world.getService(GameDataService::class.java)?.swap(repository)
        val npcData = world.getService(NpcDataService::class.java)
        val npcs = npcData?.let { service -> npcOverrides?.let { service.reload(world, it) } ?: service.reload(world) }
        val live = npcs?.let { npcData.applyToLive(world, it.changed) } ?: 0
        world.getService(DropDataService::class.java)?.reload()
        world.getService(ItemStatsService::class.java)?.apply()
        return Result(npcs?.registered ?: 0, npcs?.changed?.size ?: 0, live)
    }
}
