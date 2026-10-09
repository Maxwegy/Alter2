package dev.openrune.cache.tools.staging

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** The parts of OpenRS2's `caches.json` the stager uses. */
data class OpenRs2Cache(
    val id: Int,
    val scope: String?,
    val game: String?,
    val environment: String?,
    val language: String?,
    val builds: List<Build>?,
    val timestamp: String?,
    val size: Long?,
    val groups: Long?,
    @SerializedName("valid_groups") val validGroups: Long?,
    val keys: Long?,
    @SerializedName("valid_keys") val validKeys: Long?,
) {
    data class Build(val major: Int, val minor: Int?)

    val build: Int? get() = builds?.maxOfOrNull { it.major }
}

/** Minimal client for https://archive.openrs2.org (no stated rate limits; we make a handful of requests). */
class OpenRs2(private val base: String = "https://archive.openrs2.org") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()

    fun caches(): List<OpenRs2Cache> =
        Gson().fromJson(get("$base/caches.json"), Array<OpenRs2Cache>::class.java).toList()

    /** Live OSRS caches only, newest last. */
    fun liveOsrs(): List<OpenRs2Cache> = caches()
        .filter { it.scope == "runescape" && it.game == "oldschool" && it.environment == "live" && it.build != null && it.timestamp != null }
        .sortedWith(compareBy({ it.build }, { it.timestamp }))

    /** The newest live cache for [build], or the newest overall when [build] is null. */
    fun select(build: Int?): OpenRs2Cache {
        val candidates = liveOsrs()
        return (if (build == null) candidates else candidates.filter { it.build == build }).lastOrNull()
            ?: error("OpenRS2 has no live OSRS cache${build?.let { " for build $it" } ?: ""}.")
    }

    fun download(path: String, target: Path) {
        val response = http.send(request("$base/$path"), HttpResponse.BodyHandlers.ofFile(target))
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $base/$path" }
    }

    fun get(url: String): String {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }

    private fun request(url: String) = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "Alter2-CacheTools/1.0")
        .timeout(Duration.ofMinutes(10))
        .build()
}
