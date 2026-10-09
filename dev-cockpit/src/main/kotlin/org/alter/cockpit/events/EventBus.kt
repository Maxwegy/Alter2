package org.alter.cockpit.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Clock
import java.time.Instant

data class Event(val type: String, val payload: Any?, val at: String)

/**
 * In-process pub/sub for everything the UI should see live: inbox changes, server lifecycle, log lines,
 * missing-content hits. Late subscribers get the last [replay] events; slow ones lose the oldest.
 */
class EventBus(replay: Int = 100, private val clock: Clock = Clock.systemUTC()) {
    private val events = MutableSharedFlow<Event>(replay = replay, extraBufferCapacity = 1_000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val flow: SharedFlow<Event> get() = events

    fun publish(type: String, payload: Any? = null) {
        events.tryEmit(Event(type, payload, Instant.now(clock).toString()))
    }
}
