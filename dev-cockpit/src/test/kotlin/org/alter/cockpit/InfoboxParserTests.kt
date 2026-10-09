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
    fun `a page without an infobox parses to nothing`() {
        assertTrue(InfoboxParser.parse(Fixtures.text("Door.wikitext")).isEmpty())
        assertNull(InfoboxParser.first("plain text", "Infobox NPC"))
    }
}
