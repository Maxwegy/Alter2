package org.alter.data.drops

import kotlin.math.roundToLong

/** Integer weights over a shared [total], ready for a weighted table. [exact] is false when scaled. */
data class WeightedChances<T>(val total: Int, val weights: List<Pair<T, Int>>, val exact: Boolean)

/**
 * Converts exact chances into integer weights for the server's loot tables.
 *
 * When the denominators' least common multiple fits in [SCALE] the weights are exact (e.g. 1/512 and 4/128
 * become 1 and 16 out of 512). Otherwise (typical for tables that include the wiki's flattened herb and
 * rare-drop-table chances) every chance is scaled to [SCALE], an error below 1e-9 per entry.
 */
object LootMath {
    const val SCALE: Int = 1 shl 30

    fun <T> weigh(chances: List<Pair<T, Fraction>>): WeightedChances<T> {
        if (chances.isEmpty()) return WeightedChances(1, emptyList(), exact = true)
        val lcm = lcmOrNull(chances.map { it.second.denominator })
        if (lcm != null && lcm <= SCALE) {
            return WeightedChances(lcm.toInt(), chances.map { (value, f) -> value to (f.numerator * (lcm / f.denominator)).toInt() }, exact = true)
        }
        val weights = chances.map { (value, f) -> value to (f.value * SCALE).roundToLong().coerceIn(1, SCALE.toLong()).toInt() }.toMutableList()
        // Rounding up can push a full table past the total; take the excess from the largest entry.
        val excess = weights.sumOf { it.second.toLong() } - SCALE
        if (excess > 0) {
            val largest = weights.indices.maxBy { weights[it].second }
            weights[largest] = weights[largest].first to (weights[largest].second - excess).toInt()
        }
        return WeightedChances(SCALE, weights, exact = false)
    }

    /** A single independent chance as (weight, total), exact when the denominator fits in an Int. */
    fun single(chance: Fraction): Pair<Int, Int> =
        if (chance.denominator <= Int.MAX_VALUE) {
            chance.numerator.toInt() to chance.denominator.toInt()
        } else {
            (chance.value * SCALE).roundToLong().coerceAtLeast(1).toInt() to SCALE
        }

    private fun lcmOrNull(values: List<Long>): Long? = try {
        values.fold(1L) { acc, value -> Math.multiplyExact(acc / gcd(acc, value), value) }
    } catch (e: ArithmeticException) {
        null
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
