package org.alter.data

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * alter-data must stay usable by the Dev Cockpit, the CLI and CI without the game engine.
 * Fails the build if anyone imports engine, game-api or protocol classes here.
 */
class PurityTests {
    private val forbidden = listOf("org.alter.game", "org.alter.api", "org.alter.plugins", "net.rsprot")

    @Test
    fun `alter-data imports no engine, game-api or protocol packages`() {
        val violations = listOf("src/main/kotlin", "src/testFixtures/kotlin").map(Paths::get).filter(Files::exists)
            .flatMap { root -> Files.walk(root).use { stream -> stream.filter { it.extension == "kt" }.toList() } }
            .flatMap { file ->
                file.readLines()
                    .filter { line -> line.startsWith("import ") && forbidden.any { line.removePrefix("import ").startsWith(it) } }
                    .map { "$file: $it" }
            }
        assertTrue(violations.isEmpty(), "Forbidden imports in alter-data:\n" + violations.joinToString("\n"))
    }
}
