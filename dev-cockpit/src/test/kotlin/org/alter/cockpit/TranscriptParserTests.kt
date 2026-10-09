package org.alter.cockpit

import org.alter.cockpit.workorders.Node
import org.alter.cockpit.workorders.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptParserTests {

    @Test
    fun `Hans - greeting, a select prompt, options with lines and end actions`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Hans"))
        assertFalse(t.incomplete)
        val standard = t.sections.first()
        assertEquals("Standard dialogue", standard.title)
        assertFalse(standard.questDependent)
        assertEquals(Node.Line("Hans", "Hello. What are you doing here?"), standard.body[0])

        val options = standard.body[1] as Node.Options
        assertEquals("Select an option", options.prompt)
        assertEquals("I'm looking for whoever is in charge of this place.", options.options[0].label)
        assertEquals(
            listOf(
                Node.Line("Player", "I'm looking for whoever is in charge of this place."),
                Node.Line("Hans", "Who, the Duke? He's in his study, on the first floor."),
                Node.Action(Node.Action.END, null, "The conversation ends."),
            ),
            options.options[0].body,
        )
        // {{qact}} is a stage direction; {{overhead|...}} is inline text.
        val attack = options.options[1].body
        assertEquals(Node.Note("Hans runs away from the player screaming"), attack[1])
        assertEquals(Node.Line("Hans", "Help! Help!"), attack[2])
    }

    @Test
    fun `Man - random greetings with nested option menus`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Man"))
        val random = t.sections.first().body[0] as Node.Random
        assertTrue(random.options.size >= 20)
        val second = random.options.first { it.label == "Dialogue 2" }
        assertEquals(Node.Line("Man", "How can I help you?"), second.body[1])
        val nested = second.body[2] as Node.Options
        assertNull(nested.prompt)
        assertEquals(listOf("Do you want to trade?", "I'm in search of a quest.", "I'm in search of enemies to kill."), nested.options.map { it.label })
        val fight = random.options.first { it.label == "Dialogue 11" }
        assertTrue(fight.body.contains(Node.Note("The man attacks the player")))
    }

    @Test
    fun `Bob - a shop opening and a subsection with a condition`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Bob"))
        val options = t.sections[0].body[0] as Node.Options
        assertEquals(Node.Action(Node.Action.OPENS, "Bob's Brilliant Axes", "Opens Bob's Brilliant Axes."), options.options[1].body.last())
        assertEquals(listOf("Bob's Brilliant Axes"), t.opens())

        val repair = t.sections[1]
        assertEquals("Right click \"Repair\" option", repair.title)
        assertEquals("Standard dialogue", repair.parent)
        assertFalse(repair.questDependent)
        val condition = repair.body[0] as Node.Condition
        assertEquals("If the player has no repairable equipment:", condition.text)
        assertEquals("Bob", (condition.body[0] as Node.Line).speaker)
    }

    @Test
    fun `Shop keeper - quest section is flagged and 'above' actions are kept`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Shop keeper (Lumbridge)"))
        assertEquals(listOf("Standard dialogue", "During Death to the Dorgeshuun"), t.sections.map { it.title })
        assertFalse(t.sections[0].questDependent)
        assertTrue(t.sections[1].questDependent)
        assertEquals(listOf("Lumbridge General Store"), t.opens())
        val during = t.sections[1].body.last() as Node.Options
        assertEquals(Node.Action(Node.Action.ABOVE, null, "Same as above."), during.options[0].body.single())
    }

    @Test
    fun `Father Aereck - incomplete, quest-dependent, nested select menus`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Father Aereck"))
        assertTrue(t.incomplete)
        val before = t.sections.first()
        assertEquals("Before completion of The Restless Ghost", before.title)
        assertTrue(before.questDependent)
        val menu = before.body[1] as Node.Options
        val who = menu.options.first { it.label == "Who's Saradomin?" }
        val nested = who.body.last() as Node.Options
        assertEquals("Select an option", nested.prompt)
        assertEquals(listOf("Oh, THAT Saradomin...", "Oh, sorry. I'm not from this world."), nested.options.map { it.label })
    }

    @Test
    fun `Cook - conditions, a continuation link and a sic that disappears`() {
        val t = TranscriptParser.parse(Fixtures.transcript("Cook (Lumbridge)"))
        val after = t.sections.first()
        assertEquals("After Cook's Assistant", after.title)
        assertEquals("Standard dialogue", after.parent)
        assertTrue(after.questDependent)
        val menu = after.body[1] as Node.Options
        val quests = menu.options[0].body
        val free = quests[0] as Node.Condition
        assertEquals("If the player is on a free-to-play world:", free.text)
        assertEquals(Node.Action(Node.Action.END, null, "The conversation ends."), free.body.last())
        val members = quests[1] as Node.Condition
        val cont = members.body.single() as Node.Action
        assertEquals(Node.Action.CONTINUE, cont.kind)
        assertEquals("Transcript:Recipe for Disaster/Another Cook's Quest", cont.target)
        assertEquals(Node.Line("Player", "I am getting strong and mighty. Grrr"), menu.options[1].body[0])
    }

    @Test
    fun `clean strips links, inline templates and tags`() {
        assertEquals("Surely you've heard of Saradomin?", TranscriptParser.clean("Surely you've heard of [[Saradomin]]?"))
        assertEquals("the Duke", TranscriptParser.clean("[[Duke Horacio|the Duke]]"))
        assertEquals("Help! Help!", TranscriptParser.clean("{{overhead|Help! Help!}}"))
        assertEquals("a b", TranscriptParser.clean("''a''<br>'''b''' {{wp|adjective}}"))
    }
}
