package org.alter.data.io

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The one coroutine scope for all network and disk IO in the data layer. Nothing here runs on the
 * game thread. Closers run in reverse registration order on [close] (called from a JVM shutdown hook).
 */
class IoScope(name: String = "alter-io") : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val closers = CopyOnWriteArrayList<Pair<String, () -> Unit>>()

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName(name) +
            CoroutineExceptionHandler { _, e -> logger.error(e) { "Uncaught error in $name" } },
    )

    /** Registers [action] to run on [close], e.g. a final flush or closing an HTTP client. */
    fun onClose(label: String, action: () -> Unit) {
        closers += label to action
    }

    /** Runs [action] every [interval] until the scope is closed. Failures are logged and the loop continues. */
    fun every(interval: Duration, label: String, action: suspend () -> Unit): Job =
        scope.launch(CoroutineName(label)) {
            while (isActive) {
                delay(interval)
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Periodic task '$label' failed" }
                }
            }
        }

    override fun close() {
        closers.reversed().forEach { (label, action) ->
            try {
                action()
            } catch (e: Exception) {
                logger.error(e) { "Closer '$label' failed" }
            }
        }
        scope.cancel()
    }
}
