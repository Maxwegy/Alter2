package org.alter.plugins.content.infrastructure

import org.alter.data.snapshot.GameDataRepository
import org.alter.game.model.World
import org.alter.plugins.content.infrastructure.drops.DropDataService
import org.alter.plugins.content.infrastructure.items.ItemStatsService
import org.alter.plugins.content.infrastructure.npcs.NpcDataService

/** Swaps in a new wiki snapshot everywhere it is used. Game thread only. */
object DataReload {
    data class Result(val npcDefs: Int)

    fun apply(world: World, repository: GameDataRepository): Result {
        world.getService(GameDataService::class.java)?.swap(repository)
        val npcs = world.getService(NpcDataService::class.java)?.reload(world)
        world.getService(DropDataService::class.java)?.reload()
        world.getService(ItemStatsService::class.java)?.apply()
        return Result(npcs?.registered ?: 0)
    }
}
