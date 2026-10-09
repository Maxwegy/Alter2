package org.alter.data.http

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.alter.data.config.InfraConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Spaces requests at least [minIntervalMs] apart. The OSRS Wiki asks API users to stay sequential and gentle. */
class RateLimiter(private val minIntervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun acquire() = mutex.withLock {
        val wait = last + minIntervalMs - now()
        if (wait > 0) delay(wait)
        last = now()
    }
}

class WikiHttpException(message: String, val status: Int? = null) : IOException(message)

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}

/**
 * GETs against the OSRS Wiki `api.php`: sequential, rate limited, with a descriptive User-Agent and
 * retries (exponential backoff, honouring `Retry-After`) on network errors, 429 and 5xx.
 */
class WikiHttpClient(
    config: InfraConfig.Wiki,
    private val baseUrl: HttpUrl = DEFAULT_API.toHttpUrl(),
    private val client: OkHttpClient = defaultClient(),
    private val limiter: RateLimiter = RateLimiter(config.minRequestIntervalMs),
    private val maxRetries: Int = 3,
    private val backoffBaseMs: Long = 2_000,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    val userAgent: String = userAgent(config)

    /** Number of HTTP requests actually sent (for reports and the `--offline` zero-request check). */
    var requestCount: Int = 0
        private set

    suspend fun get(params: Map<String, String>): String {
        val url = baseUrl.newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        var attempt = 0
        while (true) {
            limiter.acquire()
            requestCount++
            val retryAfterMs = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).await().use { response ->
                    when {
                        response.isSuccessful -> return response.body?.string() ?: throw WikiHttpException("Empty body from $url", response.code)
                        response.code == 429 || response.code >= 500 ->
                            response.header("Retry-After")?.toLongOrNull()?.times(1000)
                        else -> throw WikiHttpException("HTTP ${response.code} from $url", response.code)
                    }
                }
            } catch (e: WikiHttpException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                logger.warn { "Request failed (${e.message}); retrying." }
                null
            }
            if (attempt >= maxRetries) throw WikiHttpException("Giving up on $url after ${attempt + 1} attempts")
            val backoff = (retryAfterMs ?: (backoffBaseMs shl attempt)).coerceAtMost(60_000)
            logger.warn { "Wiki asked us to back off; waiting ${backoff}ms (attempt ${attempt + 1}/$maxRetries)." }
            delay(backoff)
            attempt++
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        const val DEFAULT_API = "https://oldschool.runescape.wiki/api.php"
        const val VERSION = "1.0"

        fun userAgent(config: InfraConfig.Wiki): String {
            val details = listOf(config.repositoryUrl.takeIf { it.isNotBlank() }?.let { "+$it" }, config.contact.takeIf { it.isNotBlank() })
                .filterNotNull()
            return "Alter2-DataSync/$VERSION" + if (details.isEmpty()) "" else " (${details.joinToString("; ")})"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
