package org.alter.plugins.content.items.consumables

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.ANTIFIRE_POTION_CHARGES_ATTR
import org.alter.game.model.attr.DRAGONFIRE_IMMUNITY_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.ANTIFIRE_TIMER
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.mechanics.run.RunEnergy

/**
 * Ends the timed status effects that [ConsumablesPlugin] starts: dragonfire protection (`ANTIFIRE_TIMER`, read by
 * `DragonfireFormula` through the two antifire attributes), the stamina effect (`RunEnergy.STAMINA_BOOST`, read by
 * `RunEnergy.drain`), poison immunity (`Poison.IMMUNITY_TIMER`, read by `Poison.isImmune`) and venom immunity
 * (`Poison.VENOM_IMMUNITY_TIMER`, read by `Pawn.venom`). Everything here
 * runs on the game thread from the pawn's timers; the chat lines come from the consumables file.
 */
class StatusEffectsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val service by lazy { world.getService(ConsumablesService::class.java) }

    init {
        onTimer(ANTIFIRE_TIMER) {
            clearAntifire(player)
            service?.let { player.message(it.messages.antifireExpired) }
        }
        onTimer(ANTIFIRE_WARNING) {
            if (player.timers.has(ANTIFIRE_TIMER)) service?.let { player.message(it.messages.antifireWarning) }
        }
        onTimer(RunEnergy.STAMINA_BOOST) {
            service?.messages?.staminaExpired?.let { player.message(it) }
        }
        // Immunity simply lapses; the handler registers the key so the timer is ticked and removed.
        onTimer(Poison.IMMUNITY_TIMER) {}
        onTimer(Poison.VENOM_IMMUNITY_TIMER) {}

        onLogin {
            // The attributes persist but timers do not: protection that was running at logout has ended.
            if (!player.timers.has(ANTIFIRE_TIMER)) clearAntifire(player)
        }
        onPlayerDeath {
            // The attributes reset on death on their own (resetOnDeath); the timers must not fire afterwards.
            player.timers.remove(ANTIFIRE_TIMER)
            player.timers.remove(ANTIFIRE_WARNING)
            player.timers.remove(RunEnergy.STAMINA_BOOST)
            player.timers.remove(Poison.IMMUNITY_TIMER)
            player.timers.remove(Poison.VENOM_IMMUNITY_TIMER)
        }
    }

    companion object {
        /** Fires shortly before [ANTIFIRE_TIMER] (wiki: "about to expire" at roughly 15 seconds left). */
        val ANTIFIRE_WARNING = TimerKey()

        /** Ticks before the antifire timer ends at which the warning is sent (15 seconds). */
        const val ANTIFIRE_WARNING_TICKS = 25

        fun setAntifire(player: Player, tier: AntifireTier, ticks: Int) {
            player.attr[ANTIFIRE_POTION_CHARGES_ATTR] = 1
            player.attr[DRAGONFIRE_IMMUNITY_ATTR] = tier == AntifireTier.FULL
            player.timers[ANTIFIRE_TIMER] = ticks
            if (ticks > ANTIFIRE_WARNING_TICKS) player.timers[ANTIFIRE_WARNING] = ticks - ANTIFIRE_WARNING_TICKS else player.timers.remove(ANTIFIRE_WARNING)
        }

        fun clearAntifire(player: Player) {
            player.attr.remove(ANTIFIRE_POTION_CHARGES_ATTR)
            player.attr.remove(DRAGONFIRE_IMMUNITY_ATTR)
            player.timers.remove(ANTIFIRE_WARNING)
        }
    }
}
