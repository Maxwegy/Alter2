package org.alter.plugins.content.items.consumables

import dev.openrune.cache.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.alter.api.Skills
import org.alter.api.ext.player
import org.alter.api.ext.getInteractingItemId
import org.alter.api.ext.getInteractingItemSlot
import org.alter.api.ext.message
import org.alter.api.ext.playSound
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.service.GameService
import org.alter.plugins.content.infrastructure.InfrastructureService
import org.alter.rscm.RSCM.getRSCM

/**
 * Eating and drinking, driven by `data/cfg/consumables/consumables.json`. Every bound item goes through
 * [ConsumptionRules]; this plugin only copies state out of the player, applies the plan and talks to the client.
 * Everything runs on the game thread inside the player's plugin; the only off-thread work is the reload's parse.
 */
class ConsumablesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val logger = KotlinLogging.logger {}
    private val service = ConsumablesService()

    init {
        loadService(service)
        // The service has not initialised yet (that happens after all plugins load), so bind from the file directly.
        val table = Consumables.load(service.pathOrDefault())
        var bound = 0
        var skipped = 0
        table.consumables.forEach { c ->
            try {
                onItemOption(item = c.item, option = c.kind.verb) { consume(player) }
                bound++
            } catch (e: IllegalStateException) {
                skipped++
                logger.warn { "Not binding ${c.item}: ${e.message}" }
            }
        }
        logger.info { "Consumables: bound ${c(bound)}, skipped $skipped." }

        onCommand("reloadconsumables", Privilege.DEV_POWER, description = "Re-read data/cfg/consumables/consumables.json") {
            val infra = world.getService(InfrastructureService::class.java) ?: return@onCommand player.message("Data infrastructure is not running.")
            val game = world.getService(GameService::class.java) ?: return@onCommand player.message("No game service.")
            infra.io.scope.launch {
                val result = runCatching { service.load() }
                game.submitGameThreadJob {
                    result.onSuccess { next ->
                        service.swap(next)
                        player.message("Reloaded ${next.consumables.size} consumables. New items still need a restart to get their option bound.")
                    }.onFailure { player.message("Reload failed: ${it.message}") }
                }
            }
        }
    }

    private fun c(n: Int) = "$n option${if (n == 1) "" else "s"}"

    private fun ConsumablesService.pathOrDefault() = runCatching { path }.getOrElse { java.nio.file.Paths.get("../data/cfg/consumables/consumables.json") }

    private fun consume(player: Player) {
        val itemId = player.getInteractingItemId()
        val consumable = service.lookup(itemId) ?: return
        val slot = player.getInteractingItemSlot()
        val skills = player.getSkills()
        val state = ConsumerState(
            gates = Kind.values().associateWith { player.timers.remaining(it.gate) },
            attackDelay = player.timers.remaining(ATTACK_DELAY),
            base = IntArray(skills.maxSkills) { skills.getBaseLevel(it) },
            current = IntArray(skills.maxSkills) { skills.getCurrentLevel(it) },
            hasPrayerGear = service.prayerGearWorn.any { player.equipment.contains(it) } || service.prayerGearCarried.any { player.inventory.contains(it) },
            roll = world.random.nextDouble(),
        )
        val plan = when (val decision = ConsumptionRules.decide(consumable, state)) {
            is Decision.Blocked -> return
            is Decision.Apply -> decision.plan
        }
        if (!player.inventory.remove(item = itemId, beginSlot = slot).hasSucceeded()) return
        consumable.replacement?.let { player.inventory.add(item = getRSCM(it), beginSlot = slot) }

        player.animate(consumable.animation)
        player.playSound(consumable.sound)
        player.resetFacePawn()
        plan.levels.forEach { (skill, level) -> skills.setCurrentLevel(skill, level) }
        player.timers[consumable.kind.gate] = plan.gateTicks
        if (plan.attackDelayAdd > 0) player.timers[ATTACK_DELAY] = state.attackDelay + plan.attackDelayAdd

        val name = CacheManager.getItem(itemId).name.lowercase()
        when (consumable.kind) {
            Kind.FOOD, Kind.COMBO -> {
                player.message("You eat the $name.")
                if ((plan.levels[Skills.HITPOINTS] ?: 0) > state.current[Skills.HITPOINTS]) player.message("It heals some health.")
            }
            Kind.POTION, Kind.MIX -> {
                player.message("You drink some of your ${name.replace(Regex("\\(\\d\\)$"), "").trim()}.")
                when (val doses = consumable.replacementDoses) {
                    null -> if (consumable.replacement != null) player.message("You have finished your potion.")
                    1 -> player.message("You have 1 dose of potion left.")
                    else -> player.message("You have $doses doses of potion left.")
                }
            }
        }
    }
}

/** Remaining ticks of [key], 0 when it is not running. */
private fun org.alter.game.model.timer.TimerMap.remaining(key: org.alter.game.model.timer.TimerKey): Int =
    if (has(key)) this[key] else 0
