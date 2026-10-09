package org.alter.plugins.content.infrastructure.admin

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * `::update [ticks]`: count down, log everyone out (saved), and restart. The restart itself is done by the
 * launcher (`scripts/alter`, Docker or systemd), which starts the server again on exit code 75.
 */
class UpdateCommandPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        onCommand("update", Privilege.DEV_POWER, description = "Restart the server after a countdown: ::update [ticks]") {
            val ticks = player.getCommandArgs().firstOrNull()?.toIntOrNull() ?: 100
            if (ServerLifecycle.schedule(world, ticks, restart = true)) {
                player.message("Server restart in $ticks ticks. Players are saved at logout; the launcher starts it again.")
            } else {
                player.message("A shutdown or restart is already scheduled.")
            }
        }
    }
}
