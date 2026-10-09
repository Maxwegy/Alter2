package org.alter.game.model.weightedTableBuilder

import org.alter.game.model.Tile
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM
import kotlin.random.Random
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf

/**
 * How a [LootTable] is rolled. Every type uses the same odds: an entry's chance is `weight / total`, where
 * total is the table's `tableWeight` when positive, otherwise the sum of its entries' weights.
 *
 * - [ALWAYS]: every entry drops.
 * - [PRE_ROLL]: entries are tried in order; the first hit drops and the MAIN tables are skipped.
 * - [MAIN]: one roll picks at most one entry; weight left over (total minus the entries) drops nothing.
 * - [TERTIARY]: every entry rolls independently.
 *
 * Every table in a set is rolled, [LootTable.rolls] times.
 */
enum class TableType {
    ALWAYS,
    PRE_ROLL,
    MAIN,
    TERTIARY,
}

/**
 * @param item what drops: null for nothing, an item id, an RSCM name such as `"item.abyssal_whip"`, a nested
 *   [LootTable], or a function returning a [LootTable] or [Loot].
 */
data class Loot(
    val item: Any?,
    val min: Int,
    val max: Int = 1,
    val steepness: Int = 1,
    val weight: Int? = 0,
    val description: String? = null,
    val announce: Boolean = false,
    val block: Player.() -> Boolean = { true }
)

data class LootTable(
    val tableType: TableType,
    val tableWeight: Int? = 0,
    val drops: MutableSet<Loot>,
    val rolls: Int = 1,
) {
    init {
        require(rolls >= 1) { "A $tableType table must roll at least once." }
        if (tableType == TableType.MAIN && tableWeight != null && tableWeight > 0) {
            val sum = drops.sumOf { it.weight ?: 0 }
            check(sum <= tableWeight) { "MAIN table weights add up to $sum, more than the table weight $tableWeight." }
        }
    }

    /** The denominator for every entry's chance. */
    val total: Int
        get() = tableWeight?.takeIf { it > 0 } ?: drops.sumOf { it.weight ?: 0 }
}

/** One rolled drop: what to spawn and how many. */
data class RolledDrop(val itemId: Int, val amount: Int, val loot: Loot)

fun randomStep(start: Int, stop: Int, step: Int): Int {
    val result = (start..stop step step).toList()
    if (result.isEmpty()) {
        return start
    }
    return result.random()
}

/**
 * The MAIN-table entry selected by [roll] (`0 until total`), or null when it lands on the empty remainder.
 */
fun LootTable.pickMain(roll: Int): Loot? {
    var cumulative = 0
    for (loot in drops) {
        cumulative += loot.weight ?: 0
        if (roll < cumulative) {
            return loot
        }
    }
    return null
}

private fun LootTable.hits(loot: Loot, random: Random): Boolean {
    val weight = loot.weight ?: 0
    val total = total
    return weight > 0 && total > 0 && random.nextInt(total) < weight
}

/**
 * Rolls [tables] and returns what drops. Pure: no world access. [filter] decides whether a [Loot] is
 * eligible for the current killer (e.g. [Loot.block]).
 */
fun rollTables(
    tables: Collection<LootTable>,
    random: Random = Random.Default,
    filter: (Loot) -> Boolean = { true },
): List<RolledDrop> {
    val drops = mutableListOf<RolledDrop>()
    fun take(loot: Loot) {
        if (filter(loot)) {
            resolve(loot, random, filter, drops)
        }
    }

    tables.filter { it.tableType == TableType.ALWAYS }.forEach { table -> repeat(table.rolls) { table.drops.forEach(::take) } }
    tables.filter { it.tableType == TableType.TERTIARY }.forEach { table ->
        repeat(table.rolls) { table.drops.filter { table.hits(it, random) }.forEach(::take) }
    }
    var preRolled = false
    tables.filter { it.tableType == TableType.PRE_ROLL }.forEach { table ->
        repeat(table.rolls) {
            table.drops.firstOrNull { table.hits(it, random) }?.let { loot ->
                preRolled = true
                take(loot)
            }
        }
    }
    if (!preRolled) {
        tables.filter { it.tableType == TableType.MAIN }.forEach { table ->
            repeat(table.rolls) {
                if (table.total > 0) {
                    table.pickMain(random.nextInt(table.total))?.let(::take)
                }
            }
        }
    }
    return drops
}

private fun resolve(loot: Loot, random: Random, filter: (Loot) -> Boolean, out: MutableList<RolledDrop>) {
    fun amount() = randomStep(loot.min, loot.max, loot.steepness)
    when (val item = loot.item) {
        null -> Unit
        is Int -> out += RolledDrop(item, amount(), loot)
        is String -> out += RolledDrop(RSCM.getRSCM(item), amount(), loot)
        is LootTable -> out += rollTables(listOf(item), random, filter)
        is KFunction<*> -> when (item.returnType) {
            typeOf<LootTable>() -> out += rollTables(listOf(item.call() as LootTable), random, filter)
            typeOf<Loot>() -> resolve(item.call() as Loot, random, filter, out)
            else -> throw IllegalStateException("Unhandled LootTable return type: ${item.returnType}")
        }
        else -> throw IllegalStateException("Unhandled drop type: ${item.javaClass}")
    }
}

/**
 * Rolls [lootTables] for [p], honouring each [Loot.block]. The returned items have no position yet;
 * the caller spawns them where they belong.
 */
fun roll(p: Player, lootTables: Set<LootTable>?): Set<GroundItem> =
    rollTables(lootTables.orEmpty(), filter = { loot -> loot.block(p) })
        .map { GroundItem(it.itemId, it.amount, Tile(0, 0, 0)) }
        .toSet()
