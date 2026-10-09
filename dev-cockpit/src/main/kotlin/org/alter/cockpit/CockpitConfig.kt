package org.alter.cockpit

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/** `data/cfg/cockpit.yml`. Every field has a default, so the file may be missing or partial. */
data class CockpitConfig(
    /** Loopback only by default. Anything else exposes the inbox and server control to the network. */
    val bindAddress: String = "127.0.0.1",
    val port: Int = 43600,
    val supervisor: Supervisor = Supervisor(),
    val inbox: Inbox = Inbox(),
) {
    data class Supervisor(
        /** JVM options for a game server started by the cockpit. */
        val javaOpts: String = "-Xmx3g",
        /** The server exits with this code when it wants to be started again (`::update`). */
        val restartExitCode: Int = 75,
        /** How long a graceful stop may take before the process is killed. */
        val stopTimeoutSeconds: Long = 90,
        /** Lines of `data/logs/alter.log` kept in memory for the UI. */
        val logTailLines: Int = 500,
    )

    data class Inbox(
        /** How often `data/missing_content.json` is re-read for new cards. */
        val missingContentPollSeconds: Long = 30,
        /** Keys seen fewer times than this get no card yet. */
        val minCountForCard: Long = 1,
    )

    companion object {
        private val mapper = YAMLMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun load(path: Path): CockpitConfig = if (Files.exists(path)) mapper.readValue(path.toFile()) else CockpitConfig()
    }
}
