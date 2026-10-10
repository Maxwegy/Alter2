package org.alter.data.spawns

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.alter.data.config.InfraConfig
import org.alter.data.http.RateLimiter
import org.alter.data.http.WikiHttpClient
import org.alter.data.wiki.WikiApiException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WikiPageClientTests {
    private val server = MockWebServer().apply { start() }
    private val http = WikiHttpClient(InfraConfig.Wiki(), server.url("/api.php"), limiter = RateLimiter(0), backoffBaseMs = 1)
    private val client = WikiPageClient(http)

    @AfterTest
    fun tearDown() {
        http.close()
        server.shutdown()
    }

    @Test
    fun `embeddedin follows eicontinue`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"continue":{"eicontinue":"0|123","continue":"-||"},"query":{"embeddedin":[{"pageid":1,"ns":0,"title":"Hans"}]}}"""))
        server.enqueue(MockResponse().setBody("""{"batchcomplete":true,"query":{"embeddedin":[{"pageid":2,"ns":0,"title":"Town Crier"}]}}"""))
        assertEquals(listOf("Hans", "Town Crier"), client.embeddedIn("Template:Map"))
        val first = server.takeRequest().requestUrl!!
        assertEquals("query", first.queryParameter("action"))
        assertEquals("embeddedin", first.queryParameter("list"))
        assertEquals("Template:Map", first.queryParameter("eititle"))
        assertEquals("0", first.queryParameter("einamespace"))
        assertEquals("500", first.queryParameter("eilimit"))
        assertEquals("2", first.queryParameter("formatversion"))
        assertEquals(null, first.queryParameter("eicontinue"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("0|123", second.queryParameter("eicontinue"))
        assertEquals("-||", second.queryParameter("continue"))
    }

    @Test
    fun `wikitext in batches of 50, missing pages are null`() = runBlocking {
        val titles = (1..51).map { "Page $it" }
        val first = titles.take(50).joinToString(",") { """{"pageid":1,"ns":0,"title":"$it","revisions":[{"slots":{"main":{"contentmodel":"wikitext","content":"text of $it"}}}]}""" }
        server.enqueue(MockResponse().setBody("""{"batchcomplete":true,"query":{"pages":[$first]}}"""))
        server.enqueue(MockResponse().setBody("""{"batchcomplete":true,"query":{"normalized":[{"from":"Page 51","to":"Page 51"}],"pages":[{"ns":0,"title":"Page 51","missing":true}]}}"""))
        val texts = client.wikitext(titles)
        assertEquals(51, texts.size)
        assertEquals("text of Page 7", texts["Page 7"])
        assertEquals(null, texts["Page 51"])
        val req = server.takeRequest().requestUrl!!
        assertEquals("revisions", req.queryParameter("prop"))
        assertEquals("content", req.queryParameter("rvprop"))
        assertEquals("main", req.queryParameter("rvslots"))
        assertEquals(titles.take(50).joinToString("|"), req.queryParameter("titles"))
        assertEquals("Page 51", server.takeRequest().requestUrl!!.queryParameter("titles"))
        assertEquals(2, http.requestCount)
    }

    @Test
    fun `api errors become exceptions`() {
        server.enqueue(MockResponse().setBody("""{"error":{"code":"badvalue","info":"nope"}}"""))
        assertFailsWith<WikiApiException> { runBlocking { client.embeddedIn("Template:Map") } }
    }
}
