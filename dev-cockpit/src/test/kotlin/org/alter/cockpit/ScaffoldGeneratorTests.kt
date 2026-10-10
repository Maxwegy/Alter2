package org.alter.cockpit

import org.alter.cockpit.wiki.WikiPage
import org.alter.cockpit.workorders.ConsumableEffect
import org.alter.cockpit.workorders.ConsumableFacts
import org.alter.cockpit.workorders.ConsumableGenerator
import org.alter.cockpit.workorders.DialogueGenerator
import org.alter.cockpit.workorders.DoorGenerator
import org.alter.cockpit.workorders.Enrichment
import org.alter.cockpit.workorders.PickpocketGenerator
import org.alter.cockpit.workorders.PickpocketLine
import org.alter.cockpit.workorders.Recipe
import org.alter.cockpit.workorders.RecipeGenerator
import org.alter.cockpit.workorders.RscmNames
import org.alter.cockpit.workorders.Scaffold
import org.alter.cockpit.workorders.ScaffoldContext
import org.alter.cockpit.workorders.ScaffoldFile
import org.alter.cockpit.workorders.ScaffoldService
import org.alter.cockpit.workorders.Shop
import org.alter.cockpit.workorders.ShopGenerator
import org.alter.cockpit.workorders.SkeletonGenerator
import org.alter.cockpit.workorders.Source
import org.alter.cockpit.workorders.StockLine
import org.alter.cockpit.workorders.TranscriptParser
import org.alter.cockpit.workorders.TransportGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScaffoldGeneratorTests {
    private val rscmDir = Files.createTempDirectory("rscm").also {
        Files.writeString(it.resolve("npc.rscm"), "hans:3105\nman_3106:3106\nman_3108:3108\nshop_keeper_2813:2813\n")
        Files.writeString(it.resolve("item.rscm"), "pot:1931\njug:1935\ncoins_995:995\nbread_dough:2307\nbread:2309\n" +
            "anchovies:319\nantivenom4:12905\nantivenom3:12907\nantivenom2:12909\nantivenom1:12911\nvial_empty:229\nvial:229\n")
        Files.writeString(it.resolve("object.rscm"), "cooking_range_114:114\nladder_16683:16683\n")
    }
    private val ctx = ScaffoldContext(RscmNames(rscmDir)) { pkg -> pkg == "org.alter.plugins.content.areas.lumbridge.npcs" }
    private val service = ScaffoldService(listOf(DialogueGenerator(), ShopGenerator(), PickpocketGenerator(), DoorGenerator(), TransportGenerator(), RecipeGenerator(), ConsumableGenerator(), SkeletonGenerator()), ctx)
    private val hans = WikiPage("Hans", "https://w/Hans")

    private fun params(type: String, id: Int, name: String?, option: String?, usedId: Int? = null) =
        mapOf("type" to type, "id" to id, "name" to name, "optionName" to option, "usedId" to usedId)

    @Test
    fun `talk-to Hans becomes a dialogue plugin in the Lumbridge package`() {
        val enrichment = Enrichment(
            "talk-to", hans, facts = mapOf("name" to "Hans", "location" to "Lumbridge"),
            transcript = TranscriptParser.parse(Fixtures.transcript("Hans")), target = "dialogue plugin", sources = listOf(Source("Transcript:Hans", "https://w/Transcript:Hans")),
        )
        val scaffold = service.generate(enrichment, params("NPC_OP", 3105, "Hans", "Talk-to"))
        assertEquals("dialogue", scaffold.kind)
        assertEquals("cockpit/dialogue-hans-3105", scaffold.branch)
        val file = scaffold.files.single()
        assertEquals("game-plugins/src/main/kotlin/org/alter/plugins/content/areas/lumbridge/npcs/HansPlugin.kt", file.path)
        val code = file.content
        assertTrue(code.contains("package org.alter.plugins.content.areas.lumbridge.npcs"))
        assertTrue(code.contains("onNpcOption(\"npc.hans\", option = \"talk-to\")"))
        assertTrue(code.contains("chatNpc(player, \"Hello. What are you doing here?\")"))
        assertTrue(code.contains("when (options(player, \"I'm looking for whoever is in charge of this place.\", \"I have come to kill everyone in this castle!\""))
        assertTrue(code.contains("chatNpc(player, \"Who, the Duke? He's in his study, on the first floor.\")"))
        assertTrue(code.contains("// Hans runs away from the player screaming"))
        assertTrue(code.contains("Source: https://w/Transcript:Hans"))
        assertTrue(scaffold.manual.isEmpty() || scaffold.manual.none { it.contains("not in data/cfg/rscm") })
    }

    @Test
    fun `random greetings and unknown names are handled`() {
        val enrichment = Enrichment("talk-to", WikiPage("Man", "https://w/Man", "3"), facts = mapOf("name" to "Man"), transcript = TranscriptParser.parse(Fixtures.transcript("Man")), target = "dialogue plugin")
        val scaffold = service.generate(enrichment, params("NPC_OP", 3109, "Man", "Talk-to"))
        val code = scaffold.files.single().content
        assertTrue(code.contains("package org.alter.plugins.content.generated"))
        assertTrue(code.contains("onNpcOption(\"npc.man_3109\""))
        assertTrue(code.contains("when (Random.nextInt(23))"))
        assertTrue(code.contains("// TODO: the player receives a flyer"))
        assertTrue(scaffold.manual.any { it.contains("npc.man_3109 is not in data/cfg/rscm/npc.rscm") })
        assertTrue(scaffold.manual.any { it.startsWith("Not generated") })
    }

    @Test
    fun `trade becomes a shop plugin with RSCM item names`() {
        val shop = Shop("Lumbridge General Store", "Shop keeper", "General store", "Lumbridge", "Coins", false, listOf(StockLine("Pot", 5, 1, 0, 10, 30, 400, 1300), StockLine("Mystery box", 1, 5, 2, 10, 30, 400, 1300)))
        val enrichment = Enrichment("trade", WikiPage("Shop keeper (Lumbridge)", "https://w/Shop_keeper"), shop = shop, target = "shop config")
        val scaffold = service.generate(enrichment, params("NPC_OP", 2813, "Shop keeper", "Trade"))
        val code = scaffold.files.single().content
        assertEquals("game-plugins/src/main/kotlin/org/alter/plugins/content/areas/lumbridge/npcs/LumbridgeGeneralStorePlugin.kt", scaffold.files.single().path)
        assertTrue(code.contains("ShopItem(getRSCM(\"item.pot\"), 5, 1, 0),"))
        assertTrue(code.contains("ShopItem(getRSCM(\"item.mystery_box\"), 1, 5, 2),"))
        assertTrue(code.contains("createShop(\"Lumbridge General Store\", CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_TRADEABLES)"))
        assertTrue(code.contains("onNpcOption(\"npc.shop_keeper_2813\", option = \"trade\")"))
        assertTrue(scaffold.manual.any { it.contains("Mystery box") })
    }

    @Test
    fun `pickpocket becomes a JSON entry appended to the config`() {
        val enrichment = Enrichment("pickpocket", WikiPage("Man", "https://w/Man", "3"), pickpocket = listOf(PickpocketLine("Coins", "Always", "3", 1), PickpocketLine("Rocky", "1/257,211", "1", 1)), target = "pickpocket config")
        val scaffold = service.generate(enrichment, params("NPC_OP", 3108, "Man", "Pickpocket"))
        val file = scaffold.files.single()
        assertEquals(ScaffoldFile.JSON_APPEND, file.mode)
        assertEquals("data/cfg/thieving/pickpockets.json", file.path)
        val entry = Json.mapper.readValue(file.content, Map::class.java)
        assertEquals(listOf("npc.man_3108"), entry["npcs"])
        assertEquals(1, entry["level"])
        val loot = entry["loot"] as List<*>
        assertEquals(mapOf("item" to "item.coins_995", "min" to 3, "max" to 3, "weight" to 100.0), loot[0])
        assertEquals("item.rocky", (loot[1] as Map<*, *>)["item"])
        assertTrue(scaffold.manual.any { it.contains("experience") })
    }

    @Test
    fun `a door entry is previewed but not applyable until the other id is known`() {
        val enrichment = Enrichment("scenery", WikiPage("Door", "https://w/Door"), target = "door or gate config")
        val scaffold = service.generate(enrichment, params("LOC_OP", 1530, "Door", "Open"))
        val file = scaffold.files.single()
        assertFalse(file.applyable)
        assertTrue(file.content.contains("\"closed\" : 1530") && file.content.contains("\"opened\" : -1"))
    }

    @Test
    fun `a recipe becomes a production stub with the level check and xp`() {
        val recipe = Recipe("Bread", "1", listOf("Bread dough" to "1"), emptyList(), listOf("Cooking range"), "Cooking", 1, 40.0, 1)
        val enrichment = Enrichment("recipe", WikiPage("Cooking range (Lumbridge Castle)", "https://w/Range"), facts = mapOf("material" to "Bread dough"), recipes = listOf(recipe), target = "production action plugin")
        val scaffold = service.generate(enrichment, params("ITEM_ON_LOC", 114, "Cooking range", null, 2307))
        val code = scaffold.files.single().content
        assertTrue(code.contains("onItemOnObj(obj = \"object.cooking_range_114\", item = \"item.bread_dough\")"))
        assertTrue(code.contains("getCurrentLevel(Skills.COOKING) < 1"))
        assertTrue(code.contains("player.inventory.add(getRSCM(\"item.bread\"), 1)"))
        assertTrue(code.contains("player.addXp(Skills.COOKING, 40.0)"))
    }

    @Test
    fun `climb and unknown kinds fall back to stubs`() {
        val climb = service.generate(Enrichment("scenery", WikiPage("Ladder", "https://w/Ladder"), target = "transport stub"), params("LOC_OP", 16683, "Ladder", "Climb-up"))
        assertEquals("transport", climb.kind)
        assertTrue(climb.files.single().content.contains("onObjOption(\"object.ladder_16683\", option = \"climb-up\")"))
        val skeleton = service.generate(Enrichment("page", WikiPage("Gate", "https://w/Gate"), target = "plugin skeleton"), params("LOC_OP", 99, "Gate", "Pass"))
        assertEquals("skeleton", skeleton.kind)
        assertTrue(skeleton.files.single().content.contains("onObjOption(\"object.gate_99\", option = \"pass\")"))
    }

    private val anchovies = ConsumableFacts(
        "Anchovies", "food", listOf("Eat", "Drop"), mapOf(1 to 319), null, 1,
        "Anchovies are a type of fish that restores 1 Hitpoint when eaten.", emptyList(), emptyList(),
    )
    private val antivenom = ConsumableFacts(
        "Anti-venom", "potion", listOf("Drink", "Empty", "Drop"), mapOf(1 to 12911, 2 to 12909, 3 to 12907, 4 to 12905), 4, null, null,
        listOf(ConsumableEffect("antipoison", true, 1200), ConsumableEffect("antivenom", true, 60, "venom immunity is 36-54 seconds on the wiki")),
        emptyList(),
    )

    private fun consumable(facts: ConsumableFacts, page: String) =
        Enrichment("consumable", WikiPage(page, "https://oldschool.runescape.wiki/w/$page"), consumable = facts, target = "consumables.json entries")

    /** The scaffold's entries as one JSON array whose elements are the file contents byte for byte: the committed fixture's format. */
    private fun entries(scaffold: Scaffold) = scaffold.files.joinToString(",\n", "[\n", "\n]\n") { it.content }

    private fun fixture(name: String): String =
        ScaffoldGeneratorTests::class.java.getResourceAsStream("/scaffold/$name")!!.bufferedReader().readText().replace("\r\n", "\n")

    @Test
    fun `a food becomes one consumables entry appended to the consumables array and checked by the data test`() {
        val scaffold = service.generate(consumable(anchovies, "Anchovies"), params("INV_OP", 319, "Anchovies", "Eat"))
        assertEquals("consumable", scaffold.kind)
        val file = scaffold.files.single()
        assertEquals("data/cfg/consumables/consumables.json", file.path)
        assertEquals(ScaffoldFile.JSON_APPEND, file.mode)
        assertEquals("consumables", file.arrayKey)
        assertEquals(":game-plugins:test --tests *ConsumablesDataTests", file.verifyTask)
        assertTrue(file.applyable)
        assertEquals(fixture("anchovies.consumable.json"), entries(scaffold))
        assertTrue(scaffold.manual.any { it.contains("Combo food?") })
    }

    @Test
    fun `a potion becomes one entry per dose chained down to the vial`() {
        val scaffold = service.generate(consumable(antivenom, "Anti-venom"), params("INV_OP", 12905, "Anti-venom(4)", "Drink"))
        assertEquals(4, scaffold.files.size)
        assertTrue(scaffold.files.all { it.applyable && it.arrayKey == "consumables" && it.verifyTask != null })
        val parsed = scaffold.files.map { Json.mapper.readValue(it.content, Map::class.java) }
        assertEquals(listOf("item.antivenom4", "item.antivenom3", "item.antivenom2", "item.antivenom1"), parsed.map { it["item"] })
        assertEquals(listOf("item.antivenom3", "item.antivenom2", "item.antivenom1", "item.vial"), parsed.map { it["replacement"] })
        assertTrue(parsed.none { "heal" in it })
        assertEquals(fixture("antivenom.consumable.json"), entries(scaffold))
        assertTrue(scaffold.manual.any { it.contains("36-54") })
    }

    @Test
    fun `an entry that does nothing, or has no RSCM name, is not applyable and says why`() {
        val inert = anchovies.copy(heal = null, healSentence = null, unparsed = listOf("It boosts something unclear."))
        val scaffold = service.generate(consumable(inert, "Anchovies"), params("INV_OP", 319, "Anchovies", "Eat"))
        assertFalse(scaffold.files.single().applyable)
        assertTrue(scaffold.manual.any { it.startsWith("TODO: the page gave neither a heal nor an effect") })
        assertTrue(scaffold.manual.any { it.startsWith("TODO not parsed") && it.contains("boosts something unclear") })
        val unknown = service.generate(consumable(anchovies, "Anchovies"), params("INV_OP", 99999, "Anchovies", "Eat"))
        assertFalse(unknown.files.single().applyable)
        assertTrue(unknown.manual.any { it.contains("99999") })
    }
}
