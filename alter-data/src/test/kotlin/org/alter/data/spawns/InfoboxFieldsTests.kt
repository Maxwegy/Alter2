package org.alter.data.spawns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfoboxFieldsTests {
    private fun single(wikitext: String) = InfoboxFields.infoboxes(wikitext).single()

    @Test
    fun `Hans pairs his map with id 3105 and keeps the value raw`() {
        val box = single(Fixtures.hans)
        assertEquals("Infobox NPC", box.name)
        val pair = InfoboxFields.pairs(box).single()
        assertNull(pair.version)
        assertEquals("3105", pair.id)
        assertEquals("{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}", pair.map)
        assertTrue(pair.map!!.startsWith("{{Map"))
        assertEquals(InfoboxFields.Id.Single(3105), InfoboxFields.id(pair.id))
        // Links inside values stay raw too.
        assertEquals("[[File:Hans.png|120px]]", box["image"])
    }

    @Test
    fun `Town Crier gives seven version pairs, mapN with idN`() {
        val pairs = InfoboxFields.pairs(single(Fixtures.townCrier))
        assertEquals(7, pairs.size)
        assertEquals((1..7).toList(), pairs.map { it.version })
        assertEquals("Varrock", pairs[0].label)
        assertEquals(listOf("276", "277", "278", "279", "280", "6823", "10887"), pairs.map { it.id })
        assertEquals("{{Map|name=Town Crier|x=3254|y=3428|r=4|mtype=square}}", pairs[0].map)
        assertEquals("{{Map|name=Town Crier|x=1664|y=3669|r=4|mtype=square}}", pairs[6].map)
    }

    @Test
    fun `unnumbered parameters are shared by every version`() {
        val box = InfoboxFields.parse("Infobox NPC\n|version1 = A\n|version2 = B\n|map = {{Map|1,2|mtype=pin}}\n|id1 = 10\n|id2 = 11")
        val pairs = InfoboxFields.pairs(box)
        assertEquals(listOf("{{Map|1,2|mtype=pin}}", "{{Map|1,2|mtype=pin}}"), pairs.map { it.map })
        assertEquals(listOf("10", "11"), pairs.map { it.id })
    }

    @Test
    fun `Duke Horacio has several ids`() {
        val pair = InfoboxFields.pairs(single(Fixtures.duke)).single()
        assertEquals(InfoboxFields.Id.Ambiguous(listOf("815", "5327", "8051", "11024")), InfoboxFields.id(pair.id))
    }

    @Test
    fun `Man is an Infobox Monster without a map`() {
        val boxes = InfoboxFields.infoboxes(Fixtures.man)
        assertTrue(boxes.isNotEmpty())
        assertEquals("Infobox Monster", boxes.first().name)
        boxes.flatMap(InfoboxFields::pairs).forEach { pair ->
            assertNull(pair.map)
            assertTrue(MapTemplateParser.templates(pair.map.orEmpty()).isEmpty())
        }
    }

    @Test
    fun `ids that are missing or not numbers are reported as such`() {
        assertIs<InfoboxFields.Id.Missing>(InfoboxFields.id(null))
        assertIs<InfoboxFields.Id.Missing>(InfoboxFields.id(" "))
        assertEquals(InfoboxFields.Id.Invalid("N/A"), InfoboxFields.id("N/A"))
        assertEquals(InfoboxFields.Id.Single(2813), InfoboxFields.id(" 2813 "))
    }

    @Test
    fun `comments and nowiki blocks are ignored`() {
        val boxes = InfoboxFields.infoboxes("<!-- {{Infobox NPC|id=1}} --><nowiki>{{Infobox NPC|id=2}}</nowiki>{{Infobox NPC|id=3}}")
        assertEquals(listOf("3"), boxes.map { it["id"] })
    }
}
