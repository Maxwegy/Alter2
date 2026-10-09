package org.alter.data.wiki

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.http.WikiHttpClient
import java.io.IOException

/** A raw Bucket row: field name to value (String, Number, Boolean, or a List for repeated fields). */
typealias BucketRow = Map<String, Any?>

class WikiApiException(message: String) : IOException(message)

/** Fetches complete Bucket result sets, paging with `limit`/`offset` until a short page. */
class WikiBucketClient(private val http: WikiHttpClient) {
    suspend fun fetchAll(query: BucketQuery, pageSize: Int = MAX_PAGE): List<BucketRow> {
        val rows = mutableListOf<BucketRow>()
        var offset = 0
        while (true) {
            val page = fetchPage(query.page(pageSize, offset))
            rows += page
            if (page.size < pageSize) return rows
            offset += pageSize
        }
    }

    suspend fun fetchPage(query: BucketQuery): List<BucketRow> {
        val body = http.get(
            mapOf("action" to "bucket", "format" to "json", "formatversion" to "2", "query" to query.build()),
        )
        val json = mapper.readTree(body)
        json.get("error")?.let { throw WikiApiException("Bucket query failed: ${it.asText()} (${query.build()})") }
        val bucket = json.get("bucket") ?: throw WikiApiException("No 'bucket' field in response to ${query.build()}")
        @Suppress("UNCHECKED_CAST")
        return mapper.convertValue(bucket, List::class.java) as List<BucketRow>
    }

    companion object {
        const val MAX_PAGE = 5000
        private val mapper = ObjectMapper().registerKotlinModule()
    }
}
