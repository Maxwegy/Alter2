package org.alter.data.drops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LootMathTests {
    @Test
    fun `integer denominators give exact weights`() {
        val weighed = LootMath.weigh(listOf("whip" to Fraction(1, 512), "black sword" to Fraction(1, 32), "dagger" to Fraction(1, 32_000)))
        assertTrue(weighed.exact)
        assertEquals(64_000, weighed.total)
        // The Abyssal demon's whip stays exactly 1/512.
        assertEquals(125, weighed.weights.toMap()["whip"])
        assertEquals(2_000, weighed.weights.toMap()["black sword"])
        assertEquals(2, weighed.weights.toMap()["dagger"])
    }

    @Test
    fun `flattened decimal chances fall back to a fine scale`() {
        val herbs = listOf(Fraction(10, 269), Fraction(10, 359), Fraction(10, 479), Fraction(5, 308), Fraction(5, 392), Fraction(5, 539))
        val weighed = LootMath.weigh(herbs.mapIndexed { i, f -> i to f } + (99 to Fraction(1, 512)))
        assertEquals(LootMath.SCALE, weighed.total)
        val whip = weighed.weights.toMap().getValue(99).toDouble() / weighed.total
        assertTrue(kotlin.math.abs(whip - 1.0 / 512) < 1e-9)
    }

    @Test
    fun `rounding on the scaled path never exceeds the total`() {
        // The denominators' LCM overflows the scale and the chances sum to just under 1; rounding each one
        // to the scale overshoots by one, which must be taken back.
        val weighed = LootMath.weigh(listOf(0 to Fraction(999_999, 1_000_003), 1 to Fraction(4, 1_000_033)))
        assertEquals(LootMath.SCALE, weighed.total)
        assertTrue(weighed.weights.sumOf { it.second.toLong() } <= weighed.total)
    }

    @Test
    fun `single chances keep their own denominator`() {
        assertEquals(1 to 128, LootMath.single(Fraction(1, 128)))
        assertEquals(3 to 128, LootMath.single(Fraction(3, 128)))
    }
}
