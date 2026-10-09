package org.alter.plugins.content.infrastructure.items

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.data.ItemType
import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import it.unimi.dsi.fastutil.ints.Int2IntMaps
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import org.alter.data.items.ItemGapFill
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.alter.game.service.game.ItemMetadataService
import org.alter.plugins.content.infrastructure.GameDataService

/**
 * Fills item stats the cache lacks from the wiki snapshot (never overriding cache params or override files)
 * and publishes weapon attack ranges, which no cache param provides.
 *
 * ItemMetadataService runs first (it is a game.yml service); this runs at plugin-service init.
 */
class ItemStatsService(private val data: GameDataService) : Service {
    private val logger = KotlinLogging.logger {}
    private lateinit var world: World

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        this.world = world
        apply()
    }

    /** Re-applies the gap fill, e.g. after `::reloaditems` reset definitions from the cache. */
    fun apply() {
        val repository = data.repository
        val overridden = world.getService(ItemMetadataService::class.java)?.overriddenFields.orEmpty()
        val ranges = Int2IntOpenHashMap()
        var bonusFills = 0
        var speedFills = 0
        var skipped = 0
        val examples = mutableListOf<String>()
        CacheManager.getItems().forEach { (id, def) ->
            val wiki = repository.itemStats(id) ?: return@forEach
            wiki.attackRange?.let { ranges.put(id, it) }
            if (def.equipSlot == -1) return@forEach
            val fill = ItemGapFill.fill(def.params?.keys.orEmpty(), overridden[id].orEmpty(), wiki, isWeapon = def.equipSlot == 3)
            if (fill.isEmpty) return@forEach
            val bonuses = def.bonusesOrNull()?.copyOf() ?: run {
                skipped++
                IntArray(ItemMetadataService.BONUS_COUNT)
            }
            fill.bonuses.forEach { (index, value) -> bonuses[index] = value }
            def.bonuses = bonuses
            fill.attackSpeed?.let { def.attackSpeed = it }
            if (fill.bonuses.isNotEmpty()) bonusFills++
            if (fill.attackSpeed != null) speedFills++
            if (examples.size < 5) examples += "${def.name} ($id)"
        }
        WeaponRanges.ranges = Int2IntMaps.unmodifiable(ranges)
        logger.info {
            "Wiki item gap-fill: bonuses for $bonusFills items, attack speed for $speedFills weapons, ${ranges.size} attack ranges" +
                (if (skipped > 0) ", $skipped had no cache bonuses yet" else "") + ". e.g. ${examples.joinToString()}"
        }
    }

    private fun ItemType.bonusesOrNull(): IntArray? = try {
        bonuses
    } catch (e: UninitializedPropertyAccessException) {
        null
    }
}

/** Base weapon attack ranges from the wiki snapshot (long range adds 2 in combat). */
object WeaponRanges {
    @Volatile
    var ranges: it.unimi.dsi.fastutil.ints.Int2IntMap = Int2IntMaps.EMPTY_MAP

    fun baseRange(itemId: Int): Int? = if (ranges.containsKey(itemId)) ranges.get(itemId) else null
}
