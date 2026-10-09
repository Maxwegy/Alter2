package org.alter.data.drops

/** A parsed wiki rarity. */
sealed interface Rarity {
    data object Always : Rarity

    data class Chance(val fraction: Fraction, val approximate: Boolean) : Rarity
}

/**
 * Parses the wiki's `Rarity` strings: `Always`, `4/128`, `1/32,000`, `1/26.9`, `~1/50`.
 * Words such as `Rare`, `Varies` or `Unknown` have no exact meaning and return null (they are reported).
 */
object RarityParser {
    private val FRACTION = Regex("""^(~)?\s*([\d,]+(?:\.\d+)?)\s*/\s*([\d,]+(?:\.\d+)?)$""")

    fun parse(raw: String?): Rarity? {
        val text = raw?.trim() ?: return null
        if (text.equals("Always", ignoreCase = true)) return Rarity.Always
        val match = FRACTION.matchEntire(text) ?: return null
        val (tilde, numerator, denominator) = match.destructured
        val fraction = try {
            Fraction.ofDecimals(numerator.replace(",", ""), denominator.replace(",", ""))
        } catch (e: ArithmeticException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (fraction.numerator == 0L) return null
        return if (fraction >= Fraction.ONE) Rarity.Always else Rarity.Chance(fraction, approximate = tilde.isNotEmpty())
    }
}
