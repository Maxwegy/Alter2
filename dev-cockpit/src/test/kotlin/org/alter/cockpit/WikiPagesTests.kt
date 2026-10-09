package org.alter.cockpit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.alter.cockpit.workorders.WikiPages
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WikiPagesTests {
    private val server = MockWebServer().apply { start() }
    private val http = WikiHttpClient(InfraConfig.Wiki(minRequestIntervalMs = 0), baseUrl = server.url("/api.php"))
    private val dir = Files.createTempDirectory("pages")

    @After
    fun stop() = server.shutdown()

    private fun apiJson(title: String, content: String?): String {
        val page = if (content == null) """{"title":"$title","missing":true}""" else
            Json.mapper.writeValueAsString(mapOf("title" to title, "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("content" to content))))))
        return """{"query":{"pages":[$page]}}"""
    }

    @Test
    fun `wikitext is fetched once and then served from the disk cache`() = runBlocking {
        val hans = Fixtures.transcript("Hans")
        server.enqueue(MockResponse().setBody(apiJson("Transcript:Hans", hans)))
        val pages = WikiPages(http, dir)
        assertEquals(hans, pages.wikitext("Transcript:Hans"))
        assertEquals(hans, pages.wikitext("Transcript:Hans"))
        assertEquals(1, server.requestCount)
        assertEquals("Transcript:Hans", server.takeRequest().requestUrl!!.queryParameter("titles"))
    }

    @Test
    fun `a missing page is null and cached, and the cache expires`() = runBlocking {
        server.enqueue(MockResponse().setBody(apiJson("Transcript:Nobody", null)))
        server.enqueue(MockResponse().setBody(apiJson("Transcript:Nobody", "later")))
        val now = Instant.parse("2026-10-09T12:00:00Z")
        val pages = WikiPages(http, dir, ttl = Duration.ofHours(1), clock = Clock.fixed(now, ZoneOffset.UTC))
        assertNull(pages.wikitext("Transcript:Nobody"))
        assertNull(pages.wikitext("Transcript:Nobody"))
        assertEquals(1, server.requestCount)
        // The cache goes by the file's modification time; pin it to the fake "now" so the second clock sees it as old.
        Files.setLastModifiedTime(dir.resolve("Transcript_Nobody.wikitext"), java.nio.file.attribute.FileTime.from(now))
        val expired = WikiPages(http, dir, ttl = Duration.ofHours(1), clock = Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC))
        assertEquals("later", expired.wikitext("Transcript:Nobody"))
        assertEquals(2, server.requestCount)
    }
}
