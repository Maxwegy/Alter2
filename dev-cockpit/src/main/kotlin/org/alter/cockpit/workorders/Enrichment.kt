package org.alter.cockpit.workorders

import org.alter.cockpit.wiki.WikiPage

/** Where a fact came from. Wiki content is CC BY-NC-SA 3.0, so every work order carries its sources. */
data class Source(val title: String, val url: String, val license: String = "CC BY-NC-SA 3.0")

data class StockLine(
    val item: String,
    val stock: Int?,
    /** What the shop sells it for (`store_sell_price`). */
    val sellPrice: Int?,
    /** What the shop pays for it (`store_buy_price`). */
    val buyPrice: Int?,
    val restockTicks: Int?,
    val delta: Int?,
    val buyMultiplier: Int?,
    val sellMultiplier: Int?,
)

data class Shop(
    val name: String,
    val owner: String?,
    val specialty: String?,
    val location: String?,
    val currency: String?,
    val membersOnly: Boolean?,
    val stock: List<StockLine>,
)

data class PickpocketLine(val item: String, val rarity: String, val quantity: String, val level: Int?)

data class Recipe(
    val output: String,
    val outputQuantity: String?,
    val materials: List<Pair<String, String>>,
    val tools: List<String>,
    val facilities: List<String>,
    val skill: String?,
    val level: Int?,
    val experience: Double?,
    val ticks: Int?,
)

/**
 * Everything an enricher found for a card: the page, plain facts for the UI, structured data for the scaffold
 * generators (transcript, shop, ...), notes a human must read, and where it all came from.
 */
data class Enrichment(
    /** Which enricher ran: `talk-to`, `trade`, `pickpocket`, `scenery`, `recipe`, `page`. */
    val kind: String,
    val page: WikiPage?,
    val facts: Map<String, Any?> = emptyMap(),
    val transcript: Transcript? = null,
    val shop: Shop? = null,
    val pickpocket: List<PickpocketLine>? = null,
    val recipes: List<Recipe>? = null,
    val notes: List<String> = emptyList(),
    /** What a scaffold would be: `dialogue plugin`, `shop config`, `pickpocket entry`, `door config`, ... */
    val target: String,
    val sources: List<Source> = emptyList(),
)
