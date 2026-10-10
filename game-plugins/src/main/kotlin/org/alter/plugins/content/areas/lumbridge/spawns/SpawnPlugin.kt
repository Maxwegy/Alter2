package org.alter.plugins.content.areas.lumbridge.spawns

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

class SpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
    init {
        // Item spawns
        spawnItem(item = "item.logs", amount = 1, x = 3205, z = 3224, height = 2)
        spawnItem(item = "item.logs", amount = 1, x = 3205, z = 3226, height = 2)
        spawnItem(item = "item.logs", amount = 1, x = 3208, z = 3225, height = 2)
        spawnItem(item = "item.logs", amount = 1, x = 3209, z = 3224, height = 2)
        spawnItem(item = "item.mind_rune", amount = 1, x = 3206, z = 3208)
        spawnItem(item = "item.bronze_arrow", amount = 1, x = 3205, z = 3227)
        spawnItem(item = "item.bronze_dagger", amount = 1, x = 3213, z = 3216, height = 1)
        spawnItem(item = "item.knife", amount = 1, x = 3205, z = 3212)
        spawnItem(item = "item.knife", amount = 1, x = 3224, z = 3202)
        spawnItem(item = "item.pot", amount = 1, x = 3209, z = 3214)
        spawnItem(item = "item.bowl", amount = 1, x = 3208, z = 3214)
        spawnItem(item = "item.jug", amount = 1, x = 3211, z = 3212)

        spawnObj(obj = "object.altar_409", x = 3222, z = 3215, rot = 6)
    }
}
