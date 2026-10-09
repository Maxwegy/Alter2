package org.alter.cockpit.supervisor

import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.alter.cockpit.Json
import org.alter.data.admin.RunFile
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The game server's admin API (see `AdminControlService`), addressed through its run file. Blocking calls. */
class AdminClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
) {
    private val streaming = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** The health document, or null when the server does not answer at all. A stalled tick still answers (503). */
    fun health(run: RunFile): Map<String, Any?>? = try {
        call(run, "GET", "/health")
    } catch (e: IOException) {
        null
    }

    fun shutdown(run: RunFile, ticks: Int, restart: Boolean): Map<String, Any?> =
        call(run, "POST", "/shutdown?ticks=$ticks&restart=$restart")

    fun reloadWiki(run: RunFile): Map<String, Any?> = call(run, "POST", "/wiki/reload")

    /**
     * Reads `/events` until the connection drops, handing each event (type, JSON data) to [onEvent].
     * Comments (heartbeats) are skipped.
     */
    fun streamEvents(run: RunFile, onEvent: (type: String, data: String) -> Unit) {
        val request = Request.Builder().url("${base(run)}/events").header("Authorization", "Bearer ${run.adminToken}").build()
        streaming.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from /events")
            val reader = response.body?.charStream()?.buffered() ?: return
            var type = "message"
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) onEvent(type, data.toString())
                        type = "message"
                        data.setLength(0)
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> type = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }

    private fun call(run: RunFile, method: String, path: String): Map<String, Any?> {
        val request = Request.Builder()
            .url(base(run) + path)
            .header("Authorization", "Bearer ${run.adminToken}")
            .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed: Map<String, Any?> = if (body.isBlank()) emptyMap() else Json.mapper.readValue(body)
            if (!response.isSuccessful && response.code != 503) throw IOException("HTTP ${response.code} from $path: ${parsed["error"] ?: body}")
            return parsed + ("httpStatus" to response.code)
        }
    }

    private fun base(run: RunFile) = "http://127.0.0.1:${run.adminPort}"
}
