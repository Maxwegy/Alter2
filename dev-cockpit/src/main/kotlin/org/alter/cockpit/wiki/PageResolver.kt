package org.alter.cockpit.wiki

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A wiki page found for a game id. [anchor] is the version section when the id is one of several on the page. */
data class WikiPage(val title: String, val url: String, val anchor: String? = null)

/**
 * Finds the wiki page for a game id through `Special:Lookup?type=npc&id=N`, which answers with a redirect
 * to the page (or to the wiki's front page when nothing matches). One request per id, cached; the same
 * User-Agent and spacing as the data sync.
 */
class PageResolver(
    config: InfraConfig.Wiki,
    private val baseUrl: HttpUrl = DEFAULT_BASE.toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val minIntervalMs: Long = config.minRequestIntervalMs,
) {
    private val logger = KotlinLogging.logger {}
    private val userAgent = WikiHttpClient.userAgent(config)
    private val cache = ConcurrentHashMap<String, String?>()
    private var lastRequestAt = 0L

    fun lookupUrl(type: String, id: Int): String =
        baseUrl.newBuilder().addPathSegments("w/Special:Lookup").addQueryParameter("type", type).addQueryParameter("id", id.toString()).build().toString()

    /** The page URL, or null when the wiki has no page for this id. Blocking: call it off the request thread. */
    fun resolve(type: String, id: Int): String? = cache.computeIfAbsent("$type:$id") { fetch(type, id) }

    /** Like [resolve], but split into the page title (spaces, no namespace prefix dropped) and the version anchor. */
    fun resolvePage(type: String, id: Int): WikiPage? {
        val url = resolve(type, id)?.toHttpUrl() ?: return null
        val title = URLDecoder.decode(url.encodedPath.removePrefix("/w/"), Charsets.UTF_8).replace('_', ' ')
        return WikiPage(title, url.newBuilder().fragment(null).build().toString(), url.fragment)
    }

    private fun fetch(type: String, id: Int): String? {
        synchronized(this) {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val request = Request.Builder().url(lookupUrl(type, id)).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            val location = response.header("Location")
            logger.debug { "Special:Lookup $type $id -> ${response.code} $location" }
            if (response.code !in 300..399 || location == null) return null
            val target = baseUrl.resolve(location) ?: return null
            // No match redirects to the front page.
            return target.toString().takeIf { target.encodedPath.startsWith("/w/") }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://oldschool.runescape.wiki/"

        /** The `Special:Lookup` type for a missing-content interaction type, or null when there is none. */
        fun lookupType(interactionType: String): String? = when (interactionType) {
            "NPC_OP", "ITEM_ON_NPC", "SPELL_ON_NPC", "NPC_NO_STATS", "NPC_NO_DROPS" -> "npc"
            "LOC_OP", "ITEM_ON_LOC" -> "object"
            "INV_OP", "ITEM_ON_ITEM", "SPELL_ON_ITEM", "WORN_OP", "GROUND_OP", "ITEM_ON_GROUND", "SPELL_ON_GROUND" -> "item"
            else -> null
        }
    }
}
