package org.alter.data.wiki

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.alter.data.config.InfraConfig
import org.alter.data.http.RateLimiter
import org.alter.data.http.WikiHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WikiBucketClientTests {
    private val server = MockWebServer().apply { start() }
    private val config = InfraConfig.Wiki(contact = "dev@example.org", repositoryUrl = "https://example.org/alter2")
    private val http = WikiHttpClient(config, server.url("/api.php"), limiter = RateLimiter(0), backoffBaseMs = 1)
    private val client = WikiBucketClient(http)

    @AfterTest
    fun tearDown() {
        http.close()
        server.shutdown()
    }

    private fun rows(vararg names: String) =
        """{"bucketQuery":"q","bucket":[${names.joinToString(",") { """{"page_name":"$it","id":["1","2"]}""" }}]}"""

    @Test
    fun `pages until a short page and sends the documented parameters`() = runBlocking {
        server.enqueue(MockResponse().setBody(rows("A", "B")))
        server.enqueue(MockResponse().setBody(rows("C")))
        val result = client.fetchAll(BucketQuery("infobox_monster").select("page_name"), pageSize = 2)
        assertEquals(listOf("A", "B", "C"), result.map { it.str("page_name") })
        assertEquals(listOf(1, 2), result.first().ids("id"))

        val first = server.takeRequest()
        assertEquals("bucket", first.requestUrl!!.queryParameter("action"))
        assertEquals("2", first.requestUrl!!.queryParameter("formatversion"))
        assertTrue(first.requestUrl!!.queryParameter("query")!!.endsWith(".limit(2).offset(0).run()"))
        assertEquals("Alter2-DataSync/1.0 (+https://example.org/alter2; dev@example.org)", first.getHeader("User-Agent"))
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("query")!!.contains(".offset(2)."))
    }

    @Test
    fun `api errors become exceptions`() {
        server.enqueue(MockResponse().setBody("""{"bucketQuery":"q","error":"Field special not found"}"""))
        assertFailsWith<WikiApiException> { runBlocking { client.fetchPage(BucketQuery("infobox_shop")) } }
    }

    @Test
    fun `retries after 429 and 5xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(rows("A")))
        assertEquals(1, client.fetchAll(BucketQuery("x")).size)
        assertEquals(3, http.requestCount)
    }

    @Test
    fun `client errors are not retried`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFailsWith<Exception> { runBlocking { client.fetchPage(BucketQuery("x")) } }
        assertEquals(1, http.requestCount)
    }
}
