package org.alter.cockpit

import org.alter.cockpit.workorders.InfoboxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoboxParserTests {

    @Test
    fun `Hans - Infobox NPC with nested map template and a list value`() {
        val box = InfoboxParser.first(Fixtures.text("Hans.wikitext"), "Infobox NPC")!!
        assertEquals("Hans", box["name"])
        assertEquals("3105", box["id"])
        assertEquals("Servant of the Duke of Lumbridge.", box["examine"])
        assertEquals(listOf("Talk-to", "Age"), box.options)
        assertEquals("Lumbridge", box["location"])
        assertTrue(box["quest"]!!.contains("The Lost Tribe"))
        assertNull(box["map"]?.takeIf { it.contains("{{") })
    }

    @Test
    fun `Lumbridge General Store - Infobox Shop with piped links in the owner`() {
        val box = InfoboxParser.first(Fixtures.text("Lumbridge_General_Store.wikitext"), "Infobox Shop")!!
        assertEquals("Lumbridge General Store", box["name"])
        assertEquals("Shop keeper, Shop assistant", box["owner"])
        assertEquals("General store", box["special"])
        assertEquals("No", box["members"])
    }

    @Test
    fun `Man - a multi-version Infobox Monster narrowed to one version by its anchor`() {
        val box = InfoboxParser.first(Fixtures.text("Man.wikitext"), "Infobox Monster")!!
        assertEquals(13, box.versions.size)
        assertEquals("4, Musa Point", box.versions[5])
        val third = box.forVersion("3")
        assertEquals("Man", third["name"])
        assertEquals("3108,6989", third["id"])
        assertEquals("One of Gielinor's many citizens.", third["examine"])
        assertTrue(third.params.keys.none { it.matches(Regex(".*[0-9]+")) })
        assertEquals("One of the citizens of Al Kharid.", box.forVersion("Al Kharid")["examine"])
        // No anchor, or an unknown one, means version 1.
        assertEquals("3106,6818,6987", box.forVersion(null)["id"])
        assertEquals("3106,6818,6987", box.forVersion("nope")["id"])
    }

    @Test
    fun `Anti-venom - an item version is found by its bucket name or a normalised label`() {
        val box = InfoboxParser.first(Fixtures.text("Anti-venom.wikitext"), "Infobox Item")!!
        assertEquals(4, box.versions.size)
        // Special:Lookup for item 12905 answers with the bucket name "(4)", not the version label "4 dose".
        val four = box.forVersion("(4)")
        assertEquals("Anti-venom(4)", four["name"])
        assertEquals("12905", four["id"])
        assertEquals("(4)", four["bucketname"])
        assertEquals(listOf("Drink", "Empty", "Drop"), four.options)
        // A URL anchor spells spaces as underscores.
        assertEquals("12905", box.forVersion("4_dose")["id"])
        assertEquals("12911", box.forVersion("(1)")["id"])
    }

    @Test
    fun `a page without an infobox parses to nothing`() {
        assertTrue(InfoboxParser.parse(Fixtures.text("Door.wikitext")).isEmpty())
        assertNull(InfoboxParser.first("plain text", "Infobox NPC"))
    }
}
