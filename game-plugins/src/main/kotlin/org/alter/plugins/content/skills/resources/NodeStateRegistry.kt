package org.alter.plugins.content.skills.resources

import java.util.PriorityQueue

/**
 * Runtime node state, pure and game-thread only: depletion timers of nodes being gathered and the queue of depleted
 * nodes waiting to respawn. Keys are `(tile coordinate shl 8) or object type`, built by the plugin ([key]); [T] is
 * whatever the plugin needs to restore a node. Nothing here is ever written to disk.
 *
 * The timer (wiki Woodcutting, Oak tree, Willow tree) starts on the first tick a node is gathered and keeps
 * running while anyone gathers it; once nobody is, it resets. A gatherer counts as gathering while they
 * [touch] the node at least every [heartbeatTicks]; that is a safety net for a gatherer whose loop ended without
 * a [release], not game data.
 */
class NodeStateRegistry<T>(private val heartbeatTicks: Int) {
    init {
        require(heartbeatTicks > 0) { "heartbeatTicks must be positive" }
    }

    private class Timer(val startCycle: Int) {
        /** Gatherer id to the cycle they last touched the node. */
        val gatherers = HashMap<Int, Int>()
    }

    private data class Depleted<T>(val key: Long, val dueCycle: Int, val seq: Long, val payload: T)

    private val timers = HashMap<Long, Timer>()
    private val depleted = HashMap<Long, Depleted<T>>()
    private val queue = PriorityQueue<Depleted<T>>(compareBy<Depleted<T>>({ it.dueCycle }, { it.seq }))
    private var seq = 0L

    /** Drops gatherers not seen within the heartbeat; a timer left with nobody resets. Returns the live timer. */
    private fun live(key: Long, cycle: Int): Timer? {
        val timer = timers[key] ?: return null
        timer.gatherers.entries.removeIf { cycle - it.value > heartbeatTicks }
        if (timer.gatherers.isEmpty()) {
            timers.remove(key)
            return null
        }
        return timer
    }

    /** [who] gathers [key] this [cycle]; starts the timer if nobody was gathering it. */
    fun touch(key: Long, who: Int, cycle: Int) {
        val timer = live(key, cycle) ?: Timer(cycle).also { timers[key] = it }
        timer.gatherers[who] = cycle
    }

    /** [who] stopped gathering [key]; when nobody is left the timer resets. */
    fun release(key: Long, who: Int) {
        val timer = timers[key] ?: return
        timer.gatherers.remove(who)
        if (timer.gatherers.isEmpty()) timers.remove(key)
    }

    /** Whether [key]'s timer has run [ticks] by [cycle]; false when nobody is gathering it. */
    fun timerExpired(key: Long, ticks: Int, cycle: Int): Boolean {
        val timer = live(key, cycle) ?: return false
        return cycle - timer.startCycle >= ticks
    }

    /** Marks [key] depleted until `cycle + respawnTicks`; [payload] comes back from [due]. Clears its timer. */
    fun deplete(key: Long, cycle: Int, respawnTicks: Int, payload: T) {
        require(respawnTicks > 0) { "respawnTicks must be positive" }
        timers.remove(key)
        val entry = Depleted(key, cycle + respawnTicks, seq++, payload)
        depleted[key]?.let { queue.remove(it) }
        depleted[key] = entry
        queue.add(entry)
    }

    fun isDepleted(key: Long): Boolean = key in depleted

    /** Removes and returns, earliest first, every node due to respawn by [cycle]. */
    fun due(cycle: Int): List<T> {
        val out = ArrayList<T>()
        while (true) {
            val head = queue.peek() ?: break
            if (head.dueCycle > cycle) break
            queue.poll()
            depleted.remove(head.key)
            out += head.payload
        }
        return out
    }

    val depletedCount: Int get() = depleted.size

    companion object {
        /** The registry key of the object of [type] whose tile packs to [tile30Bit] (`Tile.as30BitInteger`). */
        fun key(tile30Bit: Int, type: Int): Long = (tile30Bit.toLong() shl 8) or (type.toLong() and 0xFF)
    }
}
