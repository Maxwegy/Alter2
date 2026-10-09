package org.alter.plugins.content.infrastructure

import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.io.IoScope
import org.alter.data.missing.MissingContentStore
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.infrastructure.missingcontent.MissingContentService

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

        loadService(InfrastructureService(io))
        if (config.missingContent.enabled) {
            loadService(
                MissingContentService(
                    store = MissingContentStore(paths.missingContent, config.missingContent.maxLocationsPerEntry),
                    io = io,
                    config = config.missingContent,
                ),
            )
        }
    }
}
