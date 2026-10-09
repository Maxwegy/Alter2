package org.alter.plugins.content.infrastructure

import gg.rsmod.util.ServerProperties
import org.alter.data.io.IoScope
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service

/**
 * Owns the data layer's [IoScope]. Its closers (final flushes, closing HTTP clients) run from a JVM
 * shutdown hook, because nothing in the server calls [Service.terminate].
 */
class InfrastructureService(val io: IoScope) : Service {
    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        Runtime.getRuntime().addShutdownHook(Thread({ io.close() }, "alter-io-shutdown"))
    }
}
