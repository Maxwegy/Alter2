package org.alter.cockpit.supervisor

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.alter.cockpit.CockpitConfig
import org.alter.cockpit.Json
import org.alter.cockpit.events.EventBus
import org.alter.data.admin.RunFile
import org.alter.data.config.DataPaths
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class ServerState { STOPPED, STARTING, RUNNING, STOPPING }

data class ServerStatus(
    val state: ServerState,
    /** True when this cockpit launched the process (it then restarts it on exit code 75). */
    val managed: Boolean,
    val pid: Long? = null,
    val startedAt: String? = null,
    val lastExitCode: Int? = null,
    val health: Map<String, Any?>? = null,
)

/**
 * Starts, stops and watches the game server. Works with a server started elsewhere too (`scripts/alter`,
 * IntelliJ): everything goes through the run file and the admin API, only start/kill need the process.
 * The admin event stream is mirrored on the bus as `server.<type>` events.
 */
class GameServerSupervisor(
    private val config: CockpitConfig.Supervisor,
    private val paths: DataPaths,
    private val admin: AdminClient,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    /** Called with the exit code whenever a managed server stops for a reason other than a restart. */
    private val onExit: (Int) -> Unit = {},
) {
    private val logger = KotlinLogging.logger {}
    private val lock = Any()
    private var process: Process? = null
    private var startedAt: String? = null
    private var stopping = false
    @Volatile private var lastExitCode: Int? = null

    private val libDir: File get() = paths.root.resolve("game-server/build/install/game-server/lib").toFile()

    fun runFile(): RunFile? = RunFile.read(paths.runFile)?.takeIf { ProcessHandle.of(it.pid).map(ProcessHandle::isAlive).orElse(false) }

    fun status(): ServerStatus {
        val managed = synchronized(lock) { process?.isAlive == true }
        val run = runFile()
        val health = run?.let(admin::health)
        val state = when {
            synchronized(lock) { stopping } && (managed || run != null) -> ServerState.STOPPING
            run != null && health != null -> ServerState.RUNNING
            managed || run != null -> ServerState.STARTING
            else -> ServerState.STOPPED
        }
        return ServerStatus(state, managed, run?.pid ?: synchronized(lock) { process?.pid() }, run?.startedAt ?: startedAt, lastExitCode, health)
    }

    /** Launches the installed distribution from `game-server/`. Returns false when a server is already up. */
    fun start(): Boolean {
        synchronized(lock) {
            if (process?.isAlive == true || runFile() != null) return false
            if (!libDir.isDirectory) throw IllegalStateException("No server distribution at $libDir; run ./gradlew :game-server:installDist")
            val console = paths.dataDir.resolve("logs/console.log").toFile()
            Files.createDirectories(console.parentFile.toPath())
            Files.deleteIfExists(paths.runFile)
            val command = listOf(javaBinary()) + config.javaOpts.split(' ').filter(String::isNotBlank) +
                listOf("-cp", libDir.absolutePath + File.separator + "*", "org.alter.game.Launcher")
            val started = ProcessBuilder(command)
                .directory(paths.root.resolve("game-server").toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(console))
                .start()
            process = started
            startedAt = Instant.now().toString()
            stopping = false
            logger.info { "Started game server pid ${started.pid()}" }
            bus.publish("server.lifecycle", mapOf("action" to "started", "pid" to started.pid()))
            scope.launch(Dispatchers.IO) { watch(started) }
            return true
        }
    }

    private suspend fun watch(started: Process) {
        val code = started.waitFor()
        logger.info { "Game server pid ${started.pid()} exited with code $code" }
        lastExitCode = code
        synchronized(lock) {
            if (process === started) process = null
            stopping = false
        }
        bus.publish("server.lifecycle", mapOf("action" to "exited", "code" to code))
        if (code == config.restartExitCode) {
            delay(1_000)
            runCatching { start() }.onFailure { logger.error(it) { "Could not restart the game server" } }
        } else {
            onExit(code)
        }
    }

    /**
     * Graceful stop through the admin API (countdown, everyone saved). When the API is unreachable and the
     * process is ours, it is destroyed instead. Returns false when nothing is running.
     */
    fun stop(ticks: Int, restart: Boolean = false): Boolean {
        val run = runFile()
        val owned = synchronized(lock) { process }
        if (run == null && owned?.isAlive != true) return false
        synchronized(lock) { stopping = true }
        bus.publish("server.lifecycle", mapOf("action" to if (restart) "restart" else "shutdown", "ticks" to ticks))
        if (run != null) {
            try {
                admin.shutdown(run, ticks, restart)
                return true
            } catch (e: Exception) {
                logger.warn(e) { "Admin API did not accept the shutdown" }
            }
        }
        if (owned != null) {
            owned.destroy()
            if (!owned.waitFor(config.stopTimeoutSeconds, TimeUnit.SECONDS)) owned.destroyForcibly()
            return true
        }
        synchronized(lock) { stopping = false }
        throw IllegalStateException("The server is running outside the cockpit and its admin API is unreachable")
    }

    fun reloadWiki(): Map<String, Any?> = admin.reloadWiki(runFile() ?: throw IllegalStateException("The server is not running"))

    /** Keeps the admin event stream open while a server runs, mirroring its events on the bus. */
    fun startEventMirror() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val run = runFile()
                if (run != null) {
                    try {
                        admin.streamEvents(run) { type, data ->
                            val payload: Any? = runCatching { Json.mapper.readValue<Map<String, Any?>>(data) }.getOrDefault(data)
                            bus.publish("server.$type", payload)
                        }
                    } catch (e: Exception) {
                        logger.debug { "Admin event stream closed: ${e.message}" }
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun javaBinary(): String {
        val home = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.home")
        val candidate = File(home, "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        return if (candidate.canExecute()) candidate.absolutePath else "java"
    }
}
