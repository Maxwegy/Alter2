package org.alter.data.drops

import java.math.BigDecimal
import java.math.BigInteger

/** An exact, reduced probability `numerator / denominator`. */
data class Fraction(val numerator: Long, val denominator: Long) : Comparable<Fraction> {
    init {
        require(denominator > 0) { "denominator must be positive: $this" }
        require(numerator >= 0) { "numerator must not be negative: $this" }
    }

    val value: Double get() = numerator.toDouble() / denominator

    operator fun plus(other: Fraction): Fraction = of(
        BigInteger.valueOf(numerator) * BigInteger.valueOf(other.denominator) + BigInteger.valueOf(other.numerator) * BigInteger.valueOf(denominator),
        BigInteger.valueOf(denominator) * BigInteger.valueOf(other.denominator),
    )

    override fun compareTo(other: Fraction): Int =
        (BigInteger.valueOf(numerator) * BigInteger.valueOf(other.denominator)).compareTo(BigInteger.valueOf(other.numerator) * BigInteger.valueOf(denominator))

    /** `[n, d]`, the snapshot's on-disk form (no string parsing at boot). */
    fun toPair(): List<Long> = listOf(numerator, denominator)

    override fun toString() = "$numerator/$denominator"

    companion object {
        val ZERO = Fraction(0, 1)
        val ONE = Fraction(1, 1)

        fun of(numerator: BigInteger, denominator: BigInteger): Fraction {
            val gcd = numerator.gcd(denominator).takeIf { it.signum() != 0 } ?: BigInteger.ONE
            return Fraction((numerator / gcd).longValueExact(), (denominator / gcd).longValueExact())
        }

        /** Exact `a / b` for decimal strings, e.g. `of("1", "26.9")` = 10/269. */
        fun ofDecimals(a: String, b: String): Fraction {
            val x = BigDecimal(a)
            val y = BigDecimal(b)
            val scale = maxOf(x.scale(), y.scale(), 0)
            return of(x.movePointRight(scale).toBigIntegerExact(), y.movePointRight(scale).toBigIntegerExact())
        }

        fun fromPair(pair: List<Long>): Fraction = Fraction(pair[0], pair[1])
    }
}
