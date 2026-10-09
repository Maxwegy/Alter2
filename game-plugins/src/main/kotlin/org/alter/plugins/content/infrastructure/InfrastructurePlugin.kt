package org.alter.plugins.content.infrastructure

import org.alter.plugins.content.infrastructure.admin.AdminControlService
import org.alter.data.admin.EventBroadcaster
import org.alter.plugins.content.infrastructure.items.ItemStatsService
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.io.IoScope
import org.alter.data.missing.MissingContentStore
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.infrastructure.drops.DropDataService
import org.alter.plugins.content.infrastructure.missingcontent.MissingContentService
import org.alter.plugins.content.infrastructure.npcs.NpcDataService

/**
 * The one place the Phase 1 data infrastructure is wired together.
 *
 * Everything below is built from plain constructor-injected classes in alter-data and registered as
 * services. Content plugins look services up once (in `onWorldInit` or `by lazy`), never per tick.
 */
class InfrastructurePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        val paths = DataPaths.default()
        val config = InfraConfig.load(paths.config)
        val io = IoScope()
        val events = EventBroadcaster()

        loadService(InfrastructureService(io))

        // Order matters: services init in registration order, all before NPCs spawn.
        val gameData = GameDataService(paths.wikiSnapshot)
        loadService(gameData)
        loadService(NpcDataService(gameData, paths.npcOverrides))
        loadService(DropDataService(gameData, paths.dropOverrides))
        loadService(ItemStatsService(gameData))
        if (config.missingContent.enabled) {
            loadService(
                MissingContentService(
                    store = MissingContentStore(paths.missingContent, config.missingContent.maxLocationsPerEntry),
                    io = io,
                    config = config.missingContent,
                    onFirstSeen = { event -> events.publish("missing", event) },
                ),
            )
        }
        if (config.admin.enabled) {
            loadService(AdminControlService(config.admin, paths.runFile, paths.wikiSnapshot, events))
        }
    }
}
