package org.alter.plugins.content.infrastructure.admin

import org.alter.game.model.World
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Graceful stop and restart, shared by the admin API and `::update`.
 *
 * Shows the client's reboot countdown, lets the reboot timer log everyone out (which saves them) when it hits
 * zero, then exits. Exit code [RESTART_EXIT_CODE] asks the launcher (`scripts/alter`, Docker's restart policy,
 * systemd) to start the server again; 0 means stay down. The JVM shutdown hook saves anyone still online.
 */
object ServerLifecycle {
    /** EX_TEMPFAIL: "try again". The alter launcher restarts on this code. */
    const val RESTART_EXIT_CODE = 75

    private val scheduled = AtomicBoolean(false)

    /** Must be called on the game thread. Returns false if a stop is already scheduled. */
    fun schedule(world: World, ticks: Int, restart: Boolean): Boolean {
        if (!scheduled.compareAndSet(false, true)) return false
        val countdown = ticks.coerceAtLeast(1)
        world.queue {
            world.rebootTimer = countdown
            world.sendRebootTimer(countdown)
            wait(countdown)
            // The reboot timer logged everyone out on its last tick; give logouts a moment to flush.
            wait(LOGOUT_GRACE_TICKS)
            exitProcess(if (restart) RESTART_EXIT_CODE else 0)
        }
        return true
    }

    val isScheduled: Boolean get() = scheduled.get()

    private const val LOGOUT_GRACE_TICKS = 5
}
