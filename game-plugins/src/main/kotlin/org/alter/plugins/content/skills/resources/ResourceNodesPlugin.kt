package org.alter.plugins.content.skills.resources

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.alter.api.Skills
import org.alter.api.ext.getInteractingGameObj
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.service.GameService
import org.alter.plugins.content.infrastructure.InfrastructureService
import org.alter.rscm.RSCM.getRSCM
import kotlin.system.exitProcess

/**
 * Woodcutting and mining nodes, driven by `data/cfg/resources/resource_nodes.json`. Every decision goes through
 * [ResourceRules]; node state lives in the service's [NodeStateRegistry]. This plugin copies state out of the
 * player, draws the random numbers, swaps objects and talks to the client; it carries no numbers or chat lines.
 * A depleted node is replaced with a [DynamicObject] and put back by one world queue, the same way
 * StallThievingPlugin does it, so no engine change is needed.
 */
class ResourceNodesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val logger = KotlinLogging.logger {}
    private val service = ResourceNodesService()

    init {
        loadService(service)
        // The service has not initialised yet (that happens after all plugins load), so bind from the file directly.
        val table = try {
            service.load(ResourceNodesService.DEFAULT_PATH)
        } catch (e: Exception) {
            logger.error(e) { "Resource nodes: ${ResourceNodesService.DEFAULT_PATH.toAbsolutePath().normalize()} does not load: ${e.message}; not starting." }
            exitProcess(1)
        }
        val unknownSkills = table.skills.keys.filter { it !in SKILL_IDS }
        if (unknownSkills.isNotEmpty()) {
            logger.error { "Resource nodes: no skill id for $unknownSkills; not starting." }
            exitProcess(1)
        }
        var bound = 0
        var mismatched = 0
        table.loaded.forEach { node ->
            val option = table.skills.getValue(node.skill).option
            node.objects.forEach { pair ->
                val problem = bindProblem(getRSCM(pair.obj), getRSCM(pair.depleted), option)
                if (problem != null) {
                    mismatched++
                    logger.warn { "Resource nodes: not binding ${pair.obj} (${node.id}): $problem" }
                    return@forEach
                }
                try {
                    onObjOption(obj = pair.obj, option = option) {
                        val obj = player.getInteractingGameObj()
                        val p = player
                        p.queue(TaskPriority.STANDARD) { gather(this, p, obj) }
                    }
                    bound++
                } catch (e: IllegalStateException) {
                    mismatched++
                    logger.warn { "Resource nodes: not binding ${pair.obj} (${node.id}): ${e.message}" }
                }
            }
        }
        logger.info { "Resource nodes: bound $bound options, skipped ${table.skipped.size} (todo), $mismatched (no option/size mismatch)." }

        onWorldInit {
            // One loop for every node: put back whatever is due this tick.
            world.queue {
                while (true) {
                    wait(1)
                    service.registry.due(world.currentCycle).forEach { r ->
                        world.spawn(DynamicObject(id = r.id, type = r.type, rot = r.rot, tile = r.tile))
                    }
                }
            }
        }

        onCommand("reloadresources", Privilege.DEV_POWER, description = "Re-read data/cfg/resources/resource_nodes.json") {
            val infra = world.getService(InfrastructureService::class.java) ?: return@onCommand player.message("Data infrastructure is not running.")
            val game = world.getService(GameService::class.java) ?: return@onCommand player.message("No game service.")
            val p = player
            infra.io.scope.launch {
                val result = runCatching { service.load() }
                game.submitGameThreadJob {
                    result.onSuccess { next ->
                        service.swap(next)
                        p.message("Reloaded resource nodes: ${service.loadedCount} loaded, ${service.skippedCount} skipped (todo). New objects still need a restart to get their option bound.")
                    }.onFailure { p.message("Reload failed: ${it.message}") }
                }
            }
        }
    }

    /** Why [obj] cannot be bound, or null: the option must exist and the depleted object must have the same footprint. */
    private fun bindProblem(obj: Int, depleted: Int, option: String): String? {
        val a = runCatching { getObject(obj) }.getOrNull() ?: return "object $obj is not in the cache"
        val b = runCatching { getObject(depleted) }.getOrNull() ?: return "depleted object $depleted is not in the cache"
        if (a.actions.none { it?.equals(option, ignoreCase = true) == true }) return "no '$option' option (${a.actions.filterNotNull()})"
        if (a.sizeX != b.sizeX || a.sizeY != b.sizeY) return "size ${a.sizeX}x${a.sizeY} vs depleted ${b.sizeX}x${b.sizeY}"
        return null
    }

    private fun say(player: Player, line: String?) {
        line?.let { player.message(it) }
    }

    /** The gather loop: check, animate, wait one roll interval, roll; until a refusal, a depletion, or an interruption. */
    private suspend fun gather(task: QueueTask, player: Player, obj: GameObject) {
        val key = NodeStateRegistry.key(obj.tile.as30BitInteger, obj.type)
        val who = player.index
        val registry = service.registry
        // Walking or another click terminates the queue; then the node is released here instead of below.
        task.terminateAction = { registry.release(key, who) }
        var first = true
        while (true) {
            if (!world.isSpawned(obj) || registry.isDepleted(key)) break
            val bound = service.lookup(obj.id) ?: break
            val node = bound.node
            val skill = service.skill(node.skill) ?: break
            val skillId = SKILL_IDS.getValue(node.skill)
            val rewardId = getRSCM(node.reward ?: break)
            val held = skill.loadedTools.filter { player.inventory.contains(getRSCM(it.item)) || player.equipment.contains(getRSCM(it.item)) }.map { it.item }.toSet()
            val start = ResourceRules.start(
                node, skill, player.getSkills().getCurrentLevel(skillId), held,
                inventoryFull = player.inventory.isFull,
                rewardStacksIntoHeld = getItem(rewardId).stackable && player.inventory.contains(rewardId),
            )
            val tool = when (start) {
                is ResourceRules.Start.Refused -> {
                    say(player, refusalMessage(start.reason))
                    break
                }
                is ResourceRules.Start.Ok -> start.tool
            }
            if (first) say(player, service.messages.start)
            first = false
            registry.touch(key, who, world.currentCycle)
            val def = obj.getDef()
            player.faceTile(obj.tile, def.sizeX, def.sizeY)
            player.animate(tool.animation ?: break)
            task.wait(ResourceRules.rollInterval(skill, tool, world.random.nextDouble()))

            if (!world.isSpawned(obj) || registry.isDepleted(key)) break
            val depletion = node.depletion
            val expired = depletion is Depletion.Timer && registry.timerExpired(key, depletion.ticks, world.currentCycle)
            val rolls = ResourceRules.Rolls(
                preRoll = world.random.nextInt(skill.preRoll?.outOf ?: 1),
                success = world.random.nextInt(256),
                tertiary = world.random.nextDouble(),
                depletion = world.random.nextDouble(),
            )
            val outcome = ResourceRules.roll(node, skill, tool, player.getSkills().getCurrentLevel(skillId), rolls, expired)
            outcome.item?.let { player.inventory.add(getRSCM(it)) }
            if (outcome.experience > 0) player.addXp(skillId, outcome.experience)
            if (outcome.gem) say(player, service.messages.gem) else if (outcome.item != null) say(player, service.messages.success)
            if (outcome.depletes) {
                deplete(obj, bound, node)
                say(player, service.messages.depleted)
                break
            }
        }
        registry.release(key, who)
        player.animate(-1)
    }

    private fun deplete(obj: GameObject, bound: ResourceNodesService.Bound, node: NodeDef) {
        val respawn = ResourceRules.respawnTicks(checkNotNull(node.respawn) { "${node.id} has no respawn" }, world.random.nextDouble())
        val key = NodeStateRegistry.key(obj.tile.as30BitInteger, obj.type)
        service.registry.deplete(key, world.currentCycle, respawn, ResourceNodesService.Restore(obj.id, obj.type, obj.rot, obj.tile))
        world.spawn(DynamicObject(obj, bound.depletedId))
    }

    private fun refusalMessage(reason: ResourceRules.Refusal): String? = when (reason) {
        ResourceRules.Refusal.LEVEL_TOO_LOW -> service.messages.levelTooLow
        ResourceRules.Refusal.NO_TOOL -> service.messages.noTool
        ResourceRules.Refusal.TOOL_LEVEL -> service.messages.toolLevelTooLow
        ResourceRules.Refusal.INVENTORY_FULL -> service.messages.inventoryFull
    }

    private companion object {
        /** File skill keys to engine skill ids. */
        val SKILL_IDS = mapOf("woodcutting" to Skills.WOODCUTTING, "mining" to Skills.MINING)
    }
}
