package org.alter.data.spawns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.alter.data.http.WikiHttpClient
import org.alter.data.wiki.WikiApiException

/**
 * MediaWiki `action=query` calls the spawn generator needs, over the shared rate-limited [WikiHttpClient]:
 * the pages that embed a template (`list=embeddedin`, paged with `eicontinue`) and page wikitext
 * (`prop=revisions&rvprop=content&rvslots=main`, at most [MAX_TITLES] titles per request).
 */
class WikiPageClient(private val http: WikiHttpClient) {
    /** Titles of the pages in [namespace] that transclude [template], in API order. */
    suspend fun embeddedIn(template: String, namespace: Int = 0): List<String> {
        val titles = mutableListOf<String>()
        query(
            mapOf("list" to "embeddedin", "eititle" to template, "einamespace" to namespace.toString(), "eilimit" to EI_LIMIT.toString()),
        ) { json -> json.path("query").path("embeddedin").forEach { node -> node.get("title")?.asText()?.let(titles::add) } }
        return titles
    }

    /** The current wikitext of each title, in batches of [batchSize] (≤ [MAX_TITLES]); null for missing pages. */
    suspend fun wikitext(titles: List<String>, batchSize: Int = MAX_TITLES, onBatch: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<String, String?> {
        require(batchSize in 1..MAX_TITLES)
        val result = LinkedHashMap<String, String?>()
        titles.chunked(batchSize).forEach { batch ->
            val renamed = HashMap<String, String>()
            query(
                mapOf("prop" to "revisions", "rvprop" to "content", "rvslots" to "main", "titles" to batch.joinToString("|")),
            ) { json ->
                json.path("query").path("normalized").forEach { renamed[it.path("to").asText()] = it.path("from").asText() }
                json.path("query").path("pages").forEach { page ->
                    val title = page.path("title").asText()
                    val key = renamed[title] ?: title
                    val content = page.path("revisions").firstOrNull()?.path("slots")?.path("main")?.get("content")?.asText()
                    if (content != null || key !in result) result[key] = content
                }
            }
            batch.forEach { if (it !in result) result[it] = null }
            onBatch(result.size, titles.size)
        }
        return result
    }

    /** Runs one query and follows every `continue` until the API has no more. */
    private suspend fun query(params: Map<String, String>, onPage: (JsonNode) -> Unit) {
        var cont: Map<String, String> = emptyMap()
        while (true) {
            val body = http.get(BASE + params + cont)
            val json = mapper.readTree(body)
            json.get("error")?.let { throw WikiApiException("Query failed: ${it.path("code").asText()} ${it.path("info").asText()} ($params)") }
            onPage(json)
            val next = json.get("continue") ?: return
            cont = next.fields().asSequence().associate { (k, v) -> k to v.asText() }
        }
    }

    companion object {
        const val MAX_TITLES = 50
        const val EI_LIMIT = 500
        private val BASE = mapOf("action" to "query", "format" to "json", "formatversion" to "2")
        private val mapper = ObjectMapper()
    }
}
