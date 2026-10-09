package org.alter.data.drops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RarityParserTests {
    private fun chance(text: String) = (RarityParser.parse(text) as Rarity.Chance)

    @Test
    fun `integer fractions are exact and reduced`() {
        assertEquals(Fraction(1, 32), chance("4/128").fraction)
        assertEquals(Fraction(1, 512), chance("1/512").fraction)
    }

    @Test
    fun `thousands separators are ignored`() = assertEquals(Fraction(1, 32_000), chance("1/32,000").fraction)

    @Test
    fun `decimal denominators become exact rationals`() {
        assertEquals(Fraction(10, 269), chance("1/26.9").fraction)
        assertEquals(Fraction(5, 8192), chance("1/1,638.4").fraction)
    }

    @Test
    fun `tilde marks an approximation`() {
        val parsed = chance("~1/50")
        assertEquals(Fraction(1, 50), parsed.fraction)
        assertEquals(true, parsed.approximate)
    }

    @Test
    fun `always and certain chances are always`() {
        assertEquals(Rarity.Always, RarityParser.parse("Always"))
        assertEquals(Rarity.Always, RarityParser.parse("1/1"))
    }

    @Test
    fun `words have no exact meaning`() {
        listOf("Rare", "Varies", "Unknown", "Very rare", "", null, "0/128").forEach { assertNull(RarityParser.parse(it), "$it") }
    }

    @Test
    fun `fractions add exactly`() = assertEquals(Fraction(1, 1), Fraction(1, 2) + Fraction(1, 4) + Fraction(1, 4))
}
