package org.alter.data.items

import org.alter.data.snapshot.ItemEntry
import kotlin.math.roundToInt

/** Values to write into an item definition: bonus index to value, plus attack speed. Empty means nothing to do. */
data class ItemFill(val bonuses: Map<Int, Int>, val attackSpeed: Int?) {
    val isEmpty: Boolean get() = bonuses.isEmpty() && attackSpeed == null
}

/**
 * Precedence for item stats is override > cache > wiki snapshot. The cache's own stats live in item params,
 * and the cache only stores non-zero params: an item with any stat param has a complete stat block in the
 * cache, and a missing param there means 0. So the wiki fills bonuses only for items with no stat params at
 * all, and attack speed only for weapons without an attack-rate param. Where the cache has stats it wins,
 * which keeps the server consistent with what the client shows.
 */
object ItemGapFill {
    /** Item params backing each `ItemType.bonuses` index (stab..ranged attack/defence, melee str, ranged str, magic dmg, prayer). */
    val BONUS_PARAMS = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 189, 299, 11)
    const val ATTACK_RATE_PARAM = 14

    fun fill(params: Set<Int>, overridden: Set<String>, wiki: ItemEntry, isWeapon: Boolean = wiki.attackSpeed != null): ItemFill {
        val cacheHasStats = BONUS_PARAMS.any { it in params }
        val wikiBonuses = with(wiki.bonuses) {
            listOf(
                stabAttack, slashAttack, crushAttack, magicAttack, rangedAttack,
                stabDefence, slashDefence, crushDefence, magicDefence, rangedDefence,
                meleeStrength, rangedStrength, magicDamage.roundToInt(), prayer,
            )
        }
        val bonuses = BONUS_PARAMS.indices
            .filter { index -> !cacheHasStats && "bonus.$index" !in overridden && wikiBonuses[index] != 0 }
            .associateWith { wikiBonuses[it] }
        val attackSpeed = wiki.attackSpeed?.takeIf { isWeapon && ATTACK_RATE_PARAM !in params && "attackSpeed" !in overridden }
        return ItemFill(bonuses, attackSpeed)
    }
}
