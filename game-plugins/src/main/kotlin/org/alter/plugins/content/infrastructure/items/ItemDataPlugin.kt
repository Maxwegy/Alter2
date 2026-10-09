package org.alter.plugins.content.infrastructure.items

import dev.openrune.cache.CacheManager
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.service.game.ItemMetadataService
import org.alter.plugins.content.infrastructure.GameDataService
import org.alter.rscm.RSCM

/** `::wikiitem <item>`: an item's live stats next to the wiki snapshot's, and which fields an override set. */
class ItemDataPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val gameData by lazy { world.getService(GameDataService::class.java) }
    private val metadata by lazy { world.getService(ItemMetadataService::class.java) }

    init {
        onCommand("wikiitem", Privilege.DEV_POWER, description = "Compare an item's stats with the wiki snapshot") {
            val arg = player.getCommandArgs().firstOrNull() ?: return@onCommand player.message("Usage: ::wikiitem <id | item.rscm_name>")
            val id = arg.toIntOrNull() ?: runCatching { RSCM.getRSCM(arg) }.getOrNull()
                ?: return@onCommand player.message("Unknown item: $arg")
            val def = runCatching { CacheManager.getItem(id) }.getOrNull() ?: return@onCommand player.message("No item $id in the cache.")
            val live = runCatching { def.bonuses.joinToString(",") }.getOrDefault("unset")
            player.message("${def.name} ($id): slot ${def.equipSlot}, speed ${def.attackSpeed}, bonuses [$live]")
            gameData?.repository?.itemStats(id)?.let { wiki ->
                val b = wiki.bonuses
                player.message("Wiki ${wiki.page}: speed ${wiki.attackSpeed}, range ${wiki.attackRange}, style ${wiki.combatStyle}")
                player.message(
                    "Wiki bonuses [${listOf(b.stabAttack, b.slashAttack, b.crushAttack, b.magicAttack, b.rangedAttack, b.stabDefence, b.slashDefence, b.crushDefence, b.magicDefence, b.rangedDefence, b.meleeStrength, b.rangedStrength, b.magicDamage, b.prayer).joinToString(",")}]",
                )
            } ?: player.message("Not in the wiki snapshot.")
            metadata?.overriddenFields?.get(id)?.let { player.message("Override sets: ${it.joinToString()}") }
        }
    }
}
