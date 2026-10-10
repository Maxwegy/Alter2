package org.alter.plugins.content.mechanics.poison

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.model.timer.POISON_TIMER
import org.alter.game.plugin.*

class PoisonPluginPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        val POISON_TICK_DELAY = 25

        onPlayerDeath {
            player.timers.remove(POISON_TIMER)
            player.timers.remove(Poison.VENOM_TIMER)
            Poison.setHpOrb(player, Poison.OrbState.NONE)
        }

        onTimer(POISON_TIMER) {
            val pawn = pawn
            val ticksLeft = pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0

            if (ticksLeft == 0) {
                if (pawn is Player) {
                    Poison.setHpOrb(pawn, Poison.OrbState.NONE)
                }
                return@onTimer
            }

            if (ticksLeft > 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft - 1
                pawn.hit(damage = Poison.getDamageForTicks(ticksLeft), type = HitType.POISON)
            } else if (ticksLeft < 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft + 1
            }

            pawn.timers[POISON_TIMER] = POISON_TICK_DELAY
        }

        // Venom: one hit every Venom.INTERVAL_TICKS, 6, 8, ... capped at 20, until cured (wiki Venom page).
        onTimer(Poison.VENOM_TIMER) {
            val pawn = pawn
            val damage = pawn.attr[Poison.VENOM_DAMAGE_ATTR] ?: return@onTimer
            pawn.hit(damage = damage, type = HitType.VENOM)
            pawn.attr[Poison.VENOM_DAMAGE_ATTR] = Venom.next(damage)
            pawn.timers[Poison.VENOM_TIMER] = Venom.INTERVAL_TICKS
        }
    }
}
