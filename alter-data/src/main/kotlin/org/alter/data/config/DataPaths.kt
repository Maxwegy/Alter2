package org.alter.data.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Every file location the data layer touches, resolved from the repo's `data/` directory.
 * The server and Gradle tasks run with `game-server/` as the working dir, hence the `../data` default.
 */
data class DataPaths(val dataDir: Path) {
    val config: Path get() = dataDir.resolve("cfg/infrastructure.yml")
    val wikiSnapshot: Path get() = dataDir.resolve("cfg/wiki")
    val npcOverrides: Path get() = dataDir.resolve("cfg/npcs/overrides")
    val dropOverrides: Path get() = dataDir.resolve("cfg/drops/overrides")
    val wikiCache: Path get() = dataDir.resolve("wiki-cache")
    val reports: Path get() = dataDir.resolve("reports")
    val missingContent: Path get() = dataDir.resolve("missing_content.json")
    val cache: Path get() = dataDir.resolve("cache")

    companion object {
        /** `-Dalter.dataDir=...` wins; otherwise `../data`, as everywhere else in the server. */
        fun default(): DataPaths = DataPaths(Paths.get(System.getProperty("alter.dataDir") ?: "../data").normalize())
    }
}
