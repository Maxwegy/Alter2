package org.alter.plugins.content.infrastructure.drops

import dev.openrune.cache.CacheManager
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.data.drops.Fraction
import org.alter.data.missing.Location
import org.alter.data.missing.MissingContentEvent
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.weightedTableBuilder.LootTable
import org.alter.game.model.weightedTableBuilder.rollTables
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.infrastructure.missingcontent.MissingContentService
import org.alter.rscm.RSCM
import kotlin.random.Random

/**
 * Rolls an NPC's drops when it dies and spawns them on its death tile, owned by the killer.
 *
 * Precedence: a non-empty `drops {}` on a hand-written combat def > drop override files > wiki snapshot.
 * The NPC is still on its death tile when `onAnyNpcDeath` fires; NpcDeathAction is not touched.
 */
class NpcDropPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val drops by lazy { world.getService(DropDataService::class.java) }
    private val missing by lazy { world.getService(MissingContentService::class.java) }

    init {
        onAnyNpcDeath {
            val npc = npc
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            val tables = tablesFor(npc)
            if (tables.isEmpty()) {
                missing?.record(MissingContentEvent("NPC_NO_DROPS", id = npc.id, name = npc.def.name, location = Location(npc.tile.x, npc.tile.z, npc.tile.height)))
                return@onAnyNpcDeath
            }
            rollTables(tables, filter = { loot -> loot.block(killer) }).forEach { drop ->
                spawn(drop.itemId, drop.amount, npc, killer)
            }
        }

        onCommand("dropsim", Privilege.DEV_POWER, description = "Simulate kills: ::dropsim <npc> [kills]") {
            val args = player.getCommandArgs()
            val id = args.firstOrNull()?.let(::npcId) ?: return@onCommand player.message("Usage: ::dropsim <id | npc.rscm_name> [kills]")
            val kills = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 10_000
            val tables = drops?.tables(id).orEmpty()
            if (tables.isEmpty()) return@onCommand player.message("NPC $id has no drop table.")
            val counts = HashMap<Int, Long>()
            val random = Random(System.nanoTime())
            repeat(kills) { rollTables(tables, random).forEach { counts.merge(it.itemId, 1L, Long::plus) } }
            player.message("$kills kills of NPC $id: ${counts.size} distinct items. Rarest:")
            counts.entries.sortedBy { it.value }.take(10).forEach { (item, count) ->
                player.message("${itemName(item)} ($item): $count, about 1/${"%.1f".format(kills.toDouble() / count)}")
            }
        }

        onCommand("wikidrops", Privilege.DEV_POWER, description = "Show an NPC's drop table: ::wikidrops <npc>") {
            val id = player.getCommandArgs().firstOrNull()?.let(::npcId) ?: return@onCommand player.message("Usage: ::wikidrops <id | npc.rscm_name>")
            val sources = drops?.sourceTables(id).orEmpty()
            if (sources.isEmpty()) return@onCommand player.message("NPC $id has no drop table.")
            val origin = if (drops?.isOverridden(id) == true) "override" else "wiki snapshot"
            sources.forEach { table ->
                player.message("${table.source} ($origin)${if (table.needsReview) " [needs review]" else ""}: ${table.always.size} always, ${table.main.size} main, ${table.tertiary.size} tertiary")
                (table.main + table.tertiary).sortedBy { it.chance?.let(Fraction::fromPair)?.value ?: 1.0 }.take(5).forEach { drop ->
                    player.message("  ${itemName(drop.item)} x${drop.min}-${drop.max} at ${drop.wiki ?: drop.chance?.joinToString("/")}")
                }
            }
        }
    }

    private fun tablesFor(npc: Npc): List<LootTable> {
        val handWritten = npc.combatDef.LootTables
        if (!handWritten.isNullOrEmpty()) return handWritten.toList()
        return drops?.tables(npc.id).orEmpty()
    }

    private fun spawn(itemId: Int, amount: Int, npc: Npc, killer: Player) {
        val stackable = runCatching { CacheManager.getItem(itemId).stackable }.getOrDefault(true)
        // Non-stackable drops of more than one appear as separate items, as in the game.
        val stacks = if (stackable) listOf(amount) else List(amount.coerceAtMost(MAX_UNSTACKED)) { 1 }
        stacks.forEach { count ->
            val item = GroundItem(itemId, count, npc.tile, killer)
            item.timeUntilPublic = world.gameContext.gItemPublicDelay
            item.timeUntilDespawn = world.gameContext.gItemDespawnDelay
            item.ownerShipType = 1
            world.spawn(item)
        }
    }

    private fun npcId(arg: String) = arg.toIntOrNull() ?: runCatching { RSCM.getRSCM(arg) }.getOrNull()

    private fun itemName(id: Int) = runCatching { CacheManager.getItem(id).name }.getOrDefault("?")

    private companion object {
        const val MAX_UNSTACKED = 28
    }
}
