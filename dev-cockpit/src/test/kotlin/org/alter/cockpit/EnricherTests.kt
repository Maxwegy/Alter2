package org.alter.cockpit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.wiki.PageResolver
import org.alter.cockpit.workorders.EnrichmentService
import org.alter.cockpit.workorders.Node
import org.alter.cockpit.workorders.PickpocketEnricher
import org.alter.cockpit.workorders.RecipeEnricher
import org.alter.cockpit.workorders.SceneryEnricher
import org.alter.cockpit.workorders.TalkToEnricher
import org.alter.cockpit.workorders.TradeEnricher
import org.alter.cockpit.workorders.WikiPages
import org.alter.cockpit.workorders.WikiUrls
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.wiki.WikiBucketClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The enrichers against a mock wiki that answers from the captured fixtures: `Special:Lookup` redirects,
 * bucket queries by the bucket they name, and page wikitext by title.
 */
class EnricherTests {
    private val server = MockWebServer()
    private val lookups = mapOf(
        "npc:3105" to "/w/Hans", "npc:2813" to "/w/Shop_keeper_(Lumbridge)", "npc:3108" to "/w/Man#3",
        "item:2307" to "/w/Bread_dough", "object:114" to "/w/Cooking_range",
    )
    private val pages = mapOf(
        "Hans" to "Hans.wikitext", "Man" to "Man.wikitext", "Transcript:Hans" to "Transcript_Hans.wikitext", "Transcript:Man" to "Transcript_Man.wikitext",
        "Transcript:Shop keeper (Lumbridge)" to "Transcript_Shop_keeper__Lumbridge_.wikitext", "Lumbridge General Store" to "Lumbridge_General_Store.wikitext",
    )
    private val bucketFixtures = mapOf(
        "infobox_npc" to "hans_npc", "infobox_monster" to "man_monster", "storeline" to "store_lumbridge", "infobox_shop" to "shop_lumbridge",
        "dropsline" to "man_all_drops", "infobox_scenery" to "door_scenery", "recipe" to "recipe_bread",
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/w/Special:Lookup") {
                    val target = lookups["${url.queryParameter("type")}:${url.queryParameter("id")}"] ?: "/"
                    return MockResponse().setResponseCode(302).setHeader("Location", target)
                }
                return when (url.queryParameter("action")) {
                    "bucket" -> {
                        val lua = url.queryParameter("query")!!
                        val fixture = bucketFixtures.entries.first { lua.startsWith("bucket('${it.key}')") }.value
                        // The Hans row only answers the Hans id; Man is not in infobox_npc at all.
                        if (fixture == "hans_npc" && "'3105'" !in lua) MockResponse().setBody("""{"bucket":[]}""")
                        else MockResponse().setBody(Fixtures.text("$fixture.bucket.json"))
                    }
                    "query" -> {
                        val title = url.queryParameter("titles")!!
                        val content = pages[title]?.let(Fixtures::text)
                        val page = if (content == null) """{"title":"$title","missing":true}""" else
                            Json.mapper.writeValueAsString(mapOf("title" to title, "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("content" to content))))))
                        MockResponse().setBody("""{"query":{"pages":[$page]}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private val wiki = InfraConfig.Wiki(minRequestIntervalMs = 0)
    private val http = WikiHttpClient(wiki, baseUrl = server.url("/api.php"))
    private val resolver = PageResolver(wiki, baseUrl = server.url("/"), minIntervalMs = 0)
    private val service = EnrichmentService(
        resolver, WikiPages(http, Files.createTempDirectory("pages")), WikiBucketClient(http), WikiUrls(),
        listOf(TalkToEnricher(), TradeEnricher(), PickpocketEnricher(), SceneryEnricher(), RecipeEnricher()),
    )

    @After
    fun stop() = server.shutdown()

    private fun params(type: String, id: Int, name: String?, option: String?, usedId: Int? = null) =
        mapOf("type" to type, "id" to id, "name" to name, "optionName" to option, "usedId" to usedId, "lookupType" to PageResolver.lookupType(type))

    @Test
    fun `talk-to Hans - infobox facts and the transcript tree`() = runBlocking {
        val e = service.enrich(params("NPC_OP", 3105, "Hans", "Talk-to"))
        assertEquals("talk-to", e.kind)
        assertEquals("Hans", e.page?.title)
        assertEquals("Servant of the Duke of Lumbridge.", e.facts["examine"])
        assertEquals("Lumbridge", e.facts["location"])
        assertEquals(listOf("Talk-to", "Age"), e.facts["options"])
        assertEquals(listOf("The Lost Tribe", "Death to the Dorgeshuun"), e.facts["quests"])
        assertEquals(Node.Line("Hans", "Hello. What are you doing here?"), e.transcript!!.sections.first().body.first())
        assertTrue(e.notes.any { it.startsWith("Quest NPC") })
        assertEquals(listOf("Hans", "Transcript:Hans"), e.sources.map { it.title })
        assertEquals("dialogue plugin", e.target)
    }

    @Test
    fun `talk-to Man - an attackable NPC comes from infobox_monster and its version 3 infobox`() = runBlocking {
        val e = service.enrich(params("NPC_OP", 3108, "Man", "Talk-to"))
        assertEquals("Man", e.page?.title)
        assertEquals("3", e.page?.anchor)
        assertEquals("Man", e.facts["name"])
        assertEquals(2, e.facts["combatLevel"])
        assertEquals("One of Gielinor's many citizens.", e.facts["examine"])
        assertEquals(emptyList<String>(), e.facts["options"])
        assertNull(e.facts["quests"])
        val random = e.transcript!!.sections.first().body.first() as Node.Random
        assertEquals(Node.Action(Node.Action.RECEIVES, "a flyer", "The player receives a flyer."), random.options.first { it.label == "Dialogue 22" }.body[2])
    }

    @Test
    fun `trade Shop keeper - the shop the transcript opens and its stock`() = runBlocking {
        val e = service.enrich(params("NPC_OP", 2813, "Shop keeper", "Trade"))
        assertEquals("trade", e.kind)
        val shop = e.shop!!
        assertEquals("Lumbridge General Store", shop.name)
        assertEquals("Shop keeper, Shop assistant", shop.owner)
        assertEquals("General store", shop.specialty)
        assertEquals("Coins", shop.currency)
        assertEquals(false, shop.membersOnly)
        assertEquals(15, shop.stock.size)
        val pot = shop.stock.first { it.item == "Pot" }
        assertEquals(5, pot.stock)
        assertEquals(1, pot.sellPrice)
        assertEquals(0, pot.buyPrice)
        assertEquals(10, pot.restockTicks)
        assertEquals(400, pot.buyMultiplier)
        assertEquals(1300, pot.sellMultiplier)
        assertEquals("shop config", e.target)
        assertTrue(e.notes.isEmpty())
    }

    @Test
    fun `pickpocket Man - only the thieving drop lines`() = runBlocking {
        val e = service.enrich(params("NPC_OP", 3108, "Man", "Pickpocket"))
        assertEquals("pickpocket", e.kind)
        assertEquals("Man", e.page?.title)
        assertEquals("3", e.page?.anchor)
        val lines = e.pickpocket!!
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.rarity.isNotBlank() && it.item.isNotBlank() })
        assertEquals("pickpocket config", e.target)
    }

    @Test
    fun `open Door - no page for the id is reported, target is the door config`() = runBlocking {
        val e = service.enrich(params("LOC_OP", 1530, "Door", "Open"))
        assertEquals("scenery", e.kind)
        assertNull(e.page)
        assertEquals("door or gate config", e.target)
        assertTrue(e.notes.single().contains("object id 1530 (Door)"))
    }

    @Test
    fun `bread dough on a cooking range - the recipe with skill, xp and ticks`() = runBlocking {
        val e = service.enrich(params("ITEM_ON_LOC", 114, "Cooking range", null, usedId = 2307))
        assertEquals("recipe", e.kind)
        val recipe = e.recipes!!.single()
        assertEquals("Bread", recipe.output)
        assertEquals(listOf("Bread dough" to "1"), recipe.materials)
        assertEquals(listOf("Cooking range"), recipe.facilities)
        assertEquals("Cooking", recipe.skill)
        assertEquals(1, recipe.level)
        assertEquals(40.0, recipe.experience!!, 0.0)
        assertEquals(1, recipe.ticks)
        assertEquals(listOf("Bread dough", "Cooking range"), e.sources.map { it.title })
    }

    @Test
    fun `an interface button has no lookup and falls through to the page enricher`() = runBlocking {
        val e = service.enrich(params("IF_BUTTON", 593, null, null))
        assertEquals("page", e.kind)
        assertNull(e.page)
        assertEquals("plugin skeleton", e.target)
        assertTrue(service.plan(params("IF_BUTTON", 593, null, null)).isEmpty())
    }

    @Test
    fun `the plan lists the lookup first, then the enricher's requests`() {
        val plan = service.plan(params("NPC_OP", 3105, "Hans", "Talk-to"))
        assertEquals(4, plan.size)
        assertTrue(plan.all { it.type == PlanStep.REQUEST })
        assertEquals(resolver.lookupUrl("npc", 3105), plan[0].target)
        assertTrue(plan[1].target!!.contains("infobox_npc"))
        assertNotNull(plan[3].target)
    }
}
