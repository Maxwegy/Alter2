package org.alter.cockpit.workorders

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alter.cockpit.Json
import org.alter.cockpit.inbox.PlanStep
import org.alter.cockpit.wiki.PageResolver
import org.alter.cockpit.wiki.WikiPage
import org.alter.data.wiki.BucketQuery
import org.alter.data.wiki.BucketRow
import org.alter.data.wiki.WikiBucketClient

/** What an enricher has to work with: the card's parameters, the resolved page, and the wiki clients. */
class EnrichContext(
    val params: Map<String, Any?>,
    val page: WikiPage?,
    val pages: WikiPages,
    val buckets: WikiBucketClient,
    val urls: WikiUrls,
    private val resolver: PageResolver,
) {
    val type: String = params["type"] as? String ?: ""
    val id: Int = (params["id"] as? Number)?.toInt() ?: -1
    val usedId: Int = (params["usedId"] as? Number)?.toInt() ?: -1
    val name: String? = params["name"] as? String
    val option: String? = params["optionName"] as? String

    private var text: String? = null
    private var loaded = false

    /** The card's own page, fetched once. */
    suspend fun wikitext(): String? {
        if (!loaded) {
            text = page?.let { pages.wikitext(it.title) }
            loaded = true
        }
        return text
    }

    suspend fun infobox(name: String): Infobox? = wikitext()?.let { InfoboxParser.first(it, name) }

    suspend fun transcript(title: String): Transcript? = pages.wikitext("Transcript:$title")?.let(TranscriptParser::parse)

    /** The page for another id, e.g. the item used on this object. */
    suspend fun resolve(type: String, id: Int): WikiPage? = withContext(Dispatchers.IO) { resolver.resolvePage(type, id) }

    fun source(title: String) = Source(title, urls.page(title))
}

/** One kind of card to one kind of fact-finding. [plan] is shown on the card before GO; [enrich] runs on GO. */
interface Enricher {
    val kind: String

    /** What a scaffold for this card would be, shown to the user. */
    val target: String

    fun supports(params: Map<String, Any?>): Boolean

    fun plan(params: Map<String, Any?>, urls: WikiUrls): List<PlanStep>

    suspend fun enrich(ctx: EnrichContext): Enrichment
}

/**
 * Resolves the card's page and runs the enricher that claims it. Cards without a wiki lookup type (interface
 * buttons) and anything no enricher claims fall through to [PageEnricher].
 */
class EnrichmentService(
    private val resolver: PageResolver,
    private val pages: WikiPages,
    private val buckets: WikiBucketClient,
    private val urls: WikiUrls,
    private val enrichers: List<Enricher>,
    private val fallback: Enricher = PageEnricher(),
) {
    fun enricherFor(params: Map<String, Any?>): Enricher = enrichers.firstOrNull { it.supports(params) } ?: fallback

    fun plan(params: Map<String, Any?>): List<PlanStep> {
        val type = params["type"] as? String ?: return emptyList()
        val id = (params["id"] as? Number)?.toInt() ?: return emptyList()
        val lookup = PageResolver.lookupType(type) ?: return emptyList()
        return listOf(PlanStep(PlanStep.REQUEST, "Find the wiki page for $lookup $id", resolver.lookupUrl(lookup, id))) +
            enricherFor(params).plan(params, urls)
    }

    suspend fun enrich(params: Map<String, Any?>): Enrichment {
        val type = params["type"] as? String ?: throw IllegalArgumentException("Card has no type")
        val id = (params["id"] as? Number)?.toInt() ?: throw IllegalArgumentException("Card has no id")
        val page = PageResolver.lookupType(type)?.let { lookup -> withContext(Dispatchers.IO) { resolver.resolvePage(lookup, id) } }
        return enricherFor(params).enrich(EnrichContext(params, page, pages, buckets, urls, resolver))
    }
}

internal fun BucketRow.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }
internal fun BucketRow.int(key: String): Int? = str(key)?.toIntOrNull()
internal fun BucketRow.strings(key: String): List<String> = (this[key] as? List<*>)?.map { it.toString() } ?: listOfNotNull(str(key))
internal fun BucketRow.json(key: String): Map<String, Any?> = str(key)?.let { runCatching { Json.mapper.readValue<Map<String, Any?>>(it) }.getOrNull() } ?: emptyMap()

private fun List<BucketRow>.preferDefault(): BucketRow? = firstOrNull { it["default_version"] == true } ?: firstOrNull()

private fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

/** `Talk-to` on an NPC: infobox facts plus the transcript as a tree. The prime automation candidate. */
class TalkToEnricher : Enricher {
    override val kind = "talk-to"
    override val target = "dialogue plugin"

    override fun supports(params: Map<String, Any?>) =
        params["type"] == "NPC_OP" && (params["optionName"] == null || (params["optionName"] as String).equals("Talk-to", true))

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) = listOf(
        PlanStep(PlanStep.REQUEST, "Read the NPC's infobox row (examine, location, quests)", urls.bucket(npcQuery(params["id"].toString()))),
        PlanStep(PlanStep.REQUEST, "Read the NPC page for its right-click options", "the page found above"),
        PlanStep(PlanStep.REQUEST, "Read and parse the Transcript: page", "Transcript:<page found above>"),
    )

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        // Non-attackable NPCs are in infobox_npc; attackable ones (most citizens too) in infobox_monster.
        val row = ctx.buckets.fetchAll(npcQuery(ctx.id.toString())).preferDefault()
            ?: ctx.buckets.fetchAll(monsterQuery(ctx.id.toString())).let { rows -> rows.firstOrNull { it.str("version_anchor") == ctx.page?.anchor } ?: rows.preferDefault() }
        val title = ctx.page?.title ?: row?.str("page_name")
            ?: return Enrichment(kind, null, notes = listOf("The wiki has no page or infobox row for npc id ${ctx.id}."), target = target)
        val page = ctx.page ?: WikiPage(title, ctx.urls.page(title))
        val infobox = (ctx.infobox("Infobox NPC") ?: ctx.infobox("Infobox Monster"))?.forVersion(page.anchor)
        // The bucket keeps the raw value: a list of "* [[Quest]]" lines, sometimes behind a MediaWiki strip marker.
        val quests = row?.str("quest")?.replace(stripMarker, "")?.lines()
            ?.map { TranscriptParser.clean(it.trim().trimStart('*')) }?.filter { it.isNotEmpty() }.orEmpty()
        val transcript = ctx.transcript(title)
        val notes = buildList {
            if (transcript == null) add("No Transcript:$title page; the dialogue must be written by hand.")
            if (transcript?.incomplete == true) add("The wiki marks the transcript as incomplete.")
            transcript?.sections?.count { it.questDependent }?.takeIf { it > 0 }?.let { add("$it section(s) depend on quest state.") }
            if (quests.isNotEmpty()) add("Quest NPC: ${quests.joinToString(", ")}. Only the standard dialogue can be scripted blindly.")
        }
        return Enrichment(
            kind = kind,
            page = page,
            facts = mapOf(
                "name" to (row?.str("npc_name") ?: row?.str("name") ?: infobox?.get("name") ?: ctx.name),
                "combatLevel" to row?.get("combat_level"),
                "examine" to (row?.str("examine") ?: infobox?.get("examine")),
                "location" to (row?.str("location")?.let(TranscriptParser::clean) ?: infobox?.get("location")),
                "options" to infobox?.options,
                "quests" to quests.ifEmpty { null },
            ),
            transcript = transcript,
            notes = notes,
            target = target,
            sources = listOfNotNull(Source(page.title, page.url), transcript?.let { ctx.source("Transcript:$title") }),
        )
    }

    private val stripMarker = Regex("""'"`UNIQ--\w+-[0-9A-Fa-f]+-QINU`"'""")

    private fun monsterQuery(id: String) = BucketQuery("infobox_monster").select("page_name", "page_name_sub", "name", "examine", "version_anchor", "combat_level", "default_version").where("id", id)

    private fun npcQuery(id: String) = BucketQuery("infobox_npc").select("page_name", "page_name_sub", "npc_name", "examine", "location", "quest", "default_version").where("npc_id", id)
}

/** `Trade` on an NPC: the shop it opens (from the transcript, else the shop whose owner it is) and the stock. */
class TradeEnricher : Enricher {
    override val kind = "trade"
    override val target = "shop config"

    override fun supports(params: Map<String, Any?>) = params["type"] == "NPC_OP" && (params["optionName"] as? String).equals("Trade", true)

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) = listOf(
        PlanStep(PlanStep.REQUEST, "Read the Transcript: page to find which shop opens", "Transcript:<page found above>"),
        PlanStep(PlanStep.REQUEST, "Read the shop's infobox row", urls.bucket(shopsQuery())),
        PlanStep(PlanStep.REQUEST, "Read the shop's stock lines", urls.bucket(stockQuery("<shop>"))),
    )

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val title = ctx.page?.title ?: return Enrichment(kind, null, notes = listOf("The wiki has no page for npc id ${ctx.id}."), target = target)
        val transcript = ctx.transcript(title)
        val shops = ctx.buckets.fetchAll(shopsQuery())
        val npcName = TranscriptParser.clean(title.substringBefore(" ("))
        val shopRow = transcript?.opens()?.firstNotNullOfOrNull { name -> shops.firstOrNull { it.str("page_name") == name || it.str("shop_name") == name } }
            ?: shops.firstOrNull { row -> row.str("owner")?.let(TranscriptParser::clean)?.contains(npcName, ignoreCase = true) == true }
        val shopName = shopRow?.str("page_name") ?: transcript?.opens()?.firstOrNull()
            ?: return Enrichment(kind, ctx.page, transcript = transcript, notes = listOf("Could not find which shop $title opens."), target = target, sources = listOf(Source(title, ctx.page.url)))
        val lines = ctx.buckets.fetchAll(stockQuery(shopName)).map { row ->
            StockLine(
                item = row.str("sold_item") ?: "?",
                stock = row.int("store_stock"),
                sellPrice = row.int("store_sell_price"),
                buyPrice = row.int("store_buy_price"),
                restockTicks = row.int("restock_time"),
                delta = row.int("store_delta"),
                buyMultiplier = row.int("store_buy_multiplier"),
                sellMultiplier = row.int("store_sell_multiplier"),
            )
        }
        val shop = Shop(
            name = shopName,
            owner = shopRow?.str("owner")?.let(TranscriptParser::clean),
            specialty = shopRow?.str("specialty"),
            location = shopRow?.str("location")?.let(TranscriptParser::clean),
            currency = ctx.buckets.fetchAll(stockQuery(shopName).select("store_currency")).firstNotNullOfOrNull { it.str("store_currency") },
            membersOnly = shopRow?.get("is_members_only") as? Boolean,
            stock = lines,
        )
        return Enrichment(
            kind = kind,
            page = ctx.page,
            facts = mapOf("shop" to shopName, "stockLines" to lines.size, "specialty" to shop.specialty),
            transcript = transcript,
            shop = shop,
            notes = if (lines.isEmpty()) listOf("The wiki lists no stock for $shopName.") else emptyList(),
            target = target,
            sources = listOf(Source(title, ctx.page.url), ctx.source(shopName)),
        )
    }

    private fun shopsQuery() = BucketQuery("infobox_shop").select("page_name", "shop_name", "owner", "location", "specialty", "is_members_only")

    private fun stockQuery(shop: String) = BucketQuery("storeline")
        .select("sold_item", "store_stock", "store_sell_price", "store_buy_price", "restock_time", "store_delta", "store_buy_multiplier", "store_sell_multiplier")
        .where("sold_by", shop)
}

/** `Pickpocket` on an NPC: the thieving lines of its drops table. */
class PickpocketEnricher : Enricher {
    override val kind = "pickpocket"
    override val target = "pickpocket config"

    override fun supports(params: Map<String, Any?>) = params["type"] == "NPC_OP" && (params["optionName"] as? String).equals("Pickpocket", true)

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) =
        listOf(PlanStep(PlanStep.REQUEST, "Read the NPC's drop lines and keep the thieving ones", urls.bucket(query("<page found above>"))))

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val title = ctx.page?.title ?: return Enrichment(kind, null, notes = listOf("The wiki has no page for npc id ${ctx.id}."), target = target)
        val lines = ctx.buckets.fetchAll(query(title)).mapNotNull { row ->
            val drop = row.json("drop_json")
            if (drop.text("Drop type") != "thieving") return@mapNotNull null
            PickpocketLine(row.str("item_name") ?: "?", drop.text("Rarity") ?: "?", drop.text("Drop Quantity") ?: "1", drop.text("Drop level")?.toIntOrNull())
        }
        return Enrichment(
            kind = kind,
            page = ctx.page,
            facts = mapOf("lines" to lines.size, "level" to lines.firstNotNullOfOrNull { it.level }),
            pickpocket = lines,
            notes = if (lines.isEmpty()) listOf("The wiki has no thieving lines for $title.") else emptyList(),
            target = target,
            sources = listOf(Source(title, ctx.page.url)),
        )
    }

    private fun query(page: String) = BucketQuery("dropsline").select("item_name", "drop_json").where("page_name", page)
}

/** An object option: page, examine and options from its infobox; the target depends on the option. */
class SceneryEnricher : Enricher {
    override val kind = "scenery"
    override val target = "object plugin"

    override fun supports(params: Map<String, Any?>) = params["type"] == "LOC_OP"

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) = listOf(
        PlanStep(PlanStep.REQUEST, "Find the object's page version by id", urls.bucket(query((params["id"] as Number).toInt()))),
        PlanStep(PlanStep.REQUEST, "Read the page's infobox (examine, options)", "the page found above"),
    )

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val row = ctx.buckets.fetchAll(query(ctx.id)).preferDefault()
        val title = ctx.page?.title ?: row?.str("page_name")
        val target = targetFor(ctx.option)
        if (title == null) {
            return Enrichment(kind, null, notes = listOf("The wiki has no page for object id ${ctx.id}${ctx.name?.let { " ($it)" } ?: ""}; the cache name is the only lead."), target = target)
        }
        val page = ctx.page ?: WikiPage(title, ctx.urls.page(title))
        val infobox = ctx.infobox("Infobox Scenery")
        return Enrichment(
            kind = kind,
            page = page,
            facts = mapOf("name" to (infobox?.get("name") ?: ctx.name), "examine" to infobox?.get("examine"), "options" to infobox?.options, "option" to ctx.option),
            notes = buildList {
                if (infobox == null) add("The page has no Infobox Scenery; it may be a generic page for many objects.")
                if (ctx.option?.startsWith("Climb", true) == true) add("The destination tile must be chosen by hand.")
            },
            target = target,
            sources = listOf(Source(page.title, page.url)),
        )
    }

    private fun targetFor(option: String?): String = when {
        option == null -> target
        option.equals("Open", true) || option.equals("Close", true) -> "door or gate config"
        option.startsWith("Climb", true) -> "transport stub"
        option.lowercase() in setOf("mine", "chop down", "chop", "fish", "net", "bait", "lure", "harpoon", "cage", "pick", "pick-from") -> "skilling action (Phase 4 template)"
        else -> target
    }

    private fun query(id: Int) = BucketQuery("infobox_scenery").select("page_name", "page_name_sub", "object_id", "default_version").where("object_id", id)
}

/** Item on item, or item on object: the production recipes that use the item (and the facility). */
class RecipeEnricher : Enricher {
    override val kind = "recipe"
    override val target = "production action plugin"

    override fun supports(params: Map<String, Any?>) = params["type"] == "ITEM_ON_ITEM" || params["type"] == "ITEM_ON_LOC"

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) = listOf(
        PlanStep(PlanStep.REQUEST, "Find the used item's page", "Special:Lookup?type=item&id=${params["usedId"]}"),
        PlanStep(PlanStep.REQUEST, "Read the recipes that use it", urls.bucket(query("<item page>"))),
    )

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val target = if (ctx.type == "ITEM_ON_ITEM") ctx.page else ctx.page
        val used = if (ctx.usedId >= 0) ctx.resolve("item", ctx.usedId) else null
        val material = used?.title ?: ctx.page?.title
            ?: return Enrichment(kind, null, notes = listOf("Neither item has a wiki page."), target = this.target)
        val facility = if (ctx.type == "ITEM_ON_LOC") ctx.page?.title else null
        val other = if (ctx.type == "ITEM_ON_ITEM") ctx.page?.title else null
        val rows = ctx.buckets.fetchAll(query(material))
        val recipes = rows.map { row ->
            val production = row.json("production_json")
            val materials = (production["materials"] as? List<*>).orEmpty().mapNotNull { m -> (m as? Map<*, *>)?.let { (it["name"]?.toString() ?: return@mapNotNull null) to (it["quantity"]?.toString() ?: "1") } }
            val skill = (production["skills"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val output = production["output"] as? Map<*, *>
            Recipe(
                output = output?.get("name")?.toString() ?: row.str("page_name") ?: "?",
                outputQuantity = output?.get("quantity")?.toString(),
                materials = materials,
                tools = row.strings("uses_tool").filter { it.isNotBlank() },
                facilities = row.strings("uses_facility").filter { it.isNotBlank() },
                skill = skill?.get("name")?.toString(),
                level = skill?.get("level")?.toString()?.toIntOrNull(),
                experience = skill?.get("experience")?.toString()?.toDoubleOrNull(),
                ticks = production["ticks"]?.toString()?.toIntOrNull(),
            )
        }.filter { recipe ->
            // Page titles carry a place ("Cooking range (Lumbridge Castle)"); recipes name the plain facility.
            (facility == null || recipe.facilities.any { sameThing(it, facility) }) &&
                (other == null || recipe.materials.any { (name, _) -> sameThing(name, other) })
        }
        return Enrichment(
            kind = kind,
            page = target,
            facts = mapOf("material" to material, "facility" to facility, "otherItem" to other, "recipes" to recipes.size),
            recipes = recipes,
            notes = if (recipes.isEmpty()) listOf("The wiki has no recipe using $material${facility?.let { " at $it" } ?: ""}${other?.let { " with $it" } ?: ""}.") else emptyList(),
            target = this.target,
            sources = listOfNotNull(used?.let { Source(it.title, it.url) }, ctx.page?.let { Source(it.title, it.url) }),
        )
    }

    private fun sameThing(a: String, b: String) = a.substringBefore(" (").equals(b.substringBefore(" ("), ignoreCase = true)

    private fun query(material: String) = BucketQuery("recipe").select("page_name", "uses_material", "uses_tool", "uses_facility", "uses_skill", "production_json").where("uses_material", material)
}

/**
 * An inventory option on an item: when the item page's infobox offers Eat or Drink, the facts for a
 * consumables.json entry (kind, dose ids, heal and status effects from the lead). Anything else is the page
 * enricher's card, as before.
 */
class ConsumableEnricher : Enricher {
    override val kind = "consumable"
    override val target = "consumables.json entries"

    override fun supports(params: Map<String, Any?>) = params["type"] == "INV_OP"

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) =
        listOf(PlanStep(PlanStep.REQUEST, "Read the item page's infobox (options, dose ids) and its lead (heal, effects)", "the page found above"))

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val page = ctx.page ?: return PageEnricher().enrich(ctx)
        val box = ctx.infobox("Infobox Item") ?: return PageEnricher().enrich(ctx)
        val version = box.forVersion(page.anchor)
        val options = version.options
        val consumableKind = when {
            options.any { it.equals("Eat", true) } -> "food"
            options.any { it.equals("Drink", true) } -> if (page.title.endsWith(" mix", true)) "mix" else "potion"
            else -> return PageEnricher().enrich(ctx)
        }
        val doseIds = doseIds(box).ifEmpty { mapOf(1 to (firstId(version["id"]) ?: ctx.id)) }
        val dose = if (box.versions.isEmpty()) null else dose(version["bucketname"], version["version"])
        val lead = lead(ctx.wikitext().orEmpty())
        val healMatch = lead.firstNotNullOfOrNull { s -> healPattern.find(s)?.let { s to it.groupValues[1].toInt() } }
        val effects = effects(lead)
        val unparsed = lead.filter { s ->
            s != healMatch?.first && effectWords.containsMatchIn(s) && !curePattern.containsMatchIn(s) && !immunityPattern.containsMatchIn(s)
        }
        val facts = ConsumableFacts(page.title, consumableKind, options, doseIds, dose, healMatch?.second, healMatch?.first, effects, unparsed)
        return Enrichment(
            kind = kind,
            page = page,
            facts = mapOf(
                "name" to page.title, "kind" to consumableKind, "options" to options, "dose" to dose, "doseIds" to doseIds,
                "heal" to facts.heal, "effects" to effects.map { it.type }.ifEmpty { null },
            ),
            consumable = facts,
            notes = buildList {
                if (facts.heal == null) add("TODO heal: the lead has no \"heals/restores N hitpoints\" sentence, so no heal is set.")
                effects.mapNotNullTo(this) { it.note }
                unparsed.forEach { add("Not parsed: $it") }
            },
            target = target,
            sources = listOf(Source(page.title, page.url)),
        )
    }

    /** `{dose: id}` from a multi-version infobox whose versions are doses (`|bucketname4 = (4)`, `|version4 = 4 dose`). */
    private fun doseIds(box: Infobox): Map<Int, Int> = box.versions.keys.mapNotNull { index ->
        val dose = dose(box["bucketname$index"], box["version$index"]) ?: return@mapNotNull null
        val id = firstId(box["id$index"]) ?: return@mapNotNull null
        dose to id
    }.toMap().toSortedMap()

    private fun dose(bucketName: String?, label: String?): Int? =
        bucketName?.let { Regex("""^\((\d+)\)$""").find(it.trim())?.groupValues?.get(1)?.toInt() }
            ?: label?.let { Regex("""^(\d+) doses?$""", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1)?.toInt() }

    private fun firstId(value: String?): Int? = value?.split(',')?.firstNotNullOfOrNull { it.trim().toIntOrNull() }

    /**
     * The sentences of the lead paragraph: the first paragraph of prose before the first heading, with templates
     * (infoboxes, switches) and file links removed, cleaned to plain text.
     */
    private fun lead(wikitext: String): List<String> {
        val body = stripTemplates(wikitext.replace("\r\n", "\n").substringBefore("\n==")).replace(fileLink, "")
        val paragraph = body.split(Regex("""\n\s*\n""")).map(TranscriptParser::clean).firstOrNull { it.isNotEmpty() } ?: return emptyList()
        return paragraph.split(Regex("""(?<=[.!?])\s+(?=[A-Z])""")).map(String::trim).filter(String::isNotEmpty)
    }

    private fun stripTemplates(text: String): String {
        val out = StringBuilder()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("{{", i) -> { depth++; i += 2 }
                text.startsWith("}}", i) && depth > 0 -> { depth--; i += 2 }
                else -> { if (depth == 0) out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    /** `cures venom and poison` and `immunity to poison for 12 minutes` into antipoison / antivenom effects. */
    private fun effects(lead: List<String>): List<ConsumableEffect> {
        val cures = mutableSetOf<String>()
        val immunity = mutableMapOf<String, Int>()
        val notes = mutableMapOf<String, String>()
        lead.forEach { s ->
            curePattern.findAll(s).forEach { m ->
                val what = m.groupValues[1].lowercase()
                if ("poison" in what) cures += "antipoison"
                if ("venom" in what) cures += "antivenom"
            }
            immunityPattern.findAll(s).forEach { m ->
                val against = m.groupValues[1].lowercase()
                val type = if (against == "venom") "antivenom" else "antipoison"
                val amount = m.groupValues[3]
                val unit = m.groupValues[4].lowercase()
                val ticks = ticks(amount.substringBefore('-').toDouble(), unit)
                immunity[type] = ticks
                when {
                    '-' in amount -> {
                        val high = ticks(amount.substringAfter('-').toDouble(), unit)
                        notes[type] = "$against immunity is $amount $unit on the wiki: modelled as the lower bound, $ticks ticks; the upper bound ($high ticks) is a TODO."
                    }
                    m.groupValues[2].isNotEmpty() ->
                        notes[type] = "$against immunity is approximately $amount $unit on the wiki: modelled as $ticks ticks; check other pages for a conflicting value."
                }
            }
        }
        return listOf("antipoison", "antivenom").filter { it in cures || it in immunity }
            .map { ConsumableEffect(it, it in cures, immunity[it], notes[it]) }
    }

    /** Minutes × 100 and seconds ÷ 0.6: one game tick is 0.6 seconds. */
    private fun ticks(amount: Double, unit: String): Int = Math.round(if (unit.startsWith("minute")) amount * 100 else amount / 0.6).toInt()

    private companion object {
        val healPattern = Regex("""(?i)\b(?:heals?|restores?)\s+(?:up to\s+)?(\d+)\s+hitpoints?\b""")
        val curePattern = Regex("""(?i)cures?\s+(venom and poison|poison and venom|poison|venom)""")
        val immunityPattern = Regex("""(?i)immunity to (poison|venom) for (approximately )?([\d.]+|\d+-\d+)\s*(seconds|minutes)""")
        val effectWords = Regex("""(?i)\b(boost|restor|heal|drain|cure|immun|poison|venom|protect)""")
        val fileLink = Regex("""\[\[File:[^\]]*]]""")
    }
}

/** Anything else: the page link and whatever infobox it has. Data gaps say what to do instead. */
class PageEnricher : Enricher {
    override val kind = "page"
    override val target = "plugin skeleton"

    override fun supports(params: Map<String, Any?>) = true

    override fun plan(params: Map<String, Any?>, urls: WikiUrls) =
        listOf(PlanStep(PlanStep.REQUEST, "Read the page's infobox", "the page found above"))

    override suspend fun enrich(ctx: EnrichContext): Enrichment {
        val target = when (ctx.type) {
            "NPC_NO_STATS" -> "NPC override (data/cfg/npcs/overrides) or a wikiSync re-run"
            "NPC_NO_DROPS" -> "drop override (data/cfg/drops/overrides) or a wikiSync re-run"
            else -> target
        }
        val page = ctx.page ?: return Enrichment(kind, null, notes = listOf("No wiki lookup for ${ctx.type} cards; this needs a hand-written fix."), target = target)
        val infobox = ctx.wikitext()?.let { InfoboxParser.parse(it).firstOrNull() }
        return Enrichment(
            kind = kind,
            page = page,
            facts = infobox?.params?.filterKeys { it in setOf("name", "examine", "options", "location", "release", "members") }.orEmpty(),
            notes = if (infobox == null) listOf("The page has no infobox.") else emptyList(),
            target = target,
            sources = listOf(Source(page.title, page.url)),
        )
    }
}
