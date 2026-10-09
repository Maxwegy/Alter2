package org.alter.cockpit.workorders

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.alter.cockpit.Json
import org.alter.data.http.WikiHttpClient
import org.alter.data.io.AtomicFiles
import org.alter.data.wiki.BucketQuery
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The URLs a work order will hit, so the inbox can show the exact plan before GO. */
class WikiUrls(private val site: String = SITE) {
    fun page(title: String): String = site.toHttpUrl().newBuilder().addPathSegment("w").addPathSegment(title.replace(' ', '_')).build().toString()

    fun bucket(query: BucketQuery): String =
        "${site}api.php?action=bucket&format=json&formatversion=2&query=" + URLEncoder.encode(query.build(), Charsets.UTF_8)

    fun wikitext(title: String): String =
        "${site}api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&titles=" + URLEncoder.encode(title, Charsets.UTF_8)

    companion object {
        const val SITE = "https://oldschool.runescape.wiki/"
    }
}

/**
 * Raw wikitext of pages (transcripts, infoboxes), fetched through the rate-limited [WikiHttpClient] and kept on
 * disk for [ttl], so pressing GO twice on a card costs one request. A missing page is cached as empty.
 */
class WikiPages(
    private val http: WikiHttpClient,
    private val cacheDir: Path,
    private val ttl: Duration = Duration.ofHours(24),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun wikitext(title: String): String? {
        val file = cacheDir.resolve(fileName(title))
        if (Files.exists(file) && Files.getLastModifiedTime(file).toInstant().plus(ttl).isAfter(Instant.now(clock))) {
            return Files.readString(file).ifEmpty { null }
        }
        val body = http.get(
            mapOf("action" to "query", "prop" to "revisions", "rvprop" to "content", "rvslots" to "main", "titles" to title, "format" to "json", "formatversion" to "2"),
        )
        val page = Json.mapper.readTree(body).path("query").path("pages").firstOrNull()
        val content = if (page == null || page.has("missing")) "" else page.path("revisions").firstOrNull()?.path("slots")?.path("main")?.path("content")?.asText().orEmpty()
        Files.createDirectories(cacheDir)
        AtomicFiles.writeText(file, content)
        return content.ifEmpty { null }
    }

    private fun fileName(title: String) = title.replace(Regex("""[^A-Za-z0-9._-]"""), "_") + ".wikitext"
}
