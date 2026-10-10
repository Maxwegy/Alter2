package org.alter.cockpit

import org.alter.cockpit.workorders.CompileFailed
import org.alter.cockpit.workorders.CompileGate
import org.alter.cockpit.workorders.CompileOutput
import org.alter.cockpit.workorders.CompileResult
import org.alter.cockpit.workorders.Scaffold
import org.alter.cockpit.workorders.ScaffoldFile
import org.alter.cockpit.workorders.ScaffoldWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CompileGateTests {
    private val repo: Path = Files.createTempDirectory("repo")
    private val workspace = ScaffoldWorkspace(repo, repo.resolve("data/cockpit/worktrees"), "main")

    private fun git(vararg args: String, dir: Path = repo): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    @Before
    fun init() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        Files.createDirectories(repo.resolve("data/cfg"))
        Files.writeString(repo.resolve("README.md"), "hello\n")
        Files.writeString(repo.resolve("data/cfg/list.json"), "[]\n")
        Files.writeString(repo.resolve(".gitignore"), "/data/cockpit/\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
    }

    private class FakeGate(private val result: CompileResult) : CompileGate {
        override val task = result.task
        val compiled = mutableListOf<Path>()

        override fun compile(worktree: Path): CompileResult {
            compiled.add(worktree) // Path is Iterable<Path>, so += would add its elements
            return result
        }
    }

    private val kotlinScaffold = Scaffold("dialogue", "Hans dialogue", "cockpit/dialogue-hans-3105", listOf(ScaffoldFile("src/HansPlugin.kt", "class HansPlugin\n")))
    private val jsonScaffold = Scaffold("door", "A door", "cockpit/door-1", listOf(ScaffoldFile("data/cfg/list.json", "{\"b\": 2}", ScaffoldFile.JSON_APPEND)))

    @Test
    fun `a failing gate leaves the files staged and commits nothing`() {
        val gate = FakeGate(CompileResult(false, listOf("e: src/HansPlugin.kt:1:1 Expecting a top level declaration"), 1234, ":game-plugins:compileKotlin"))
        val failure = assertThrows(CompileFailed::class.java) { workspace.apply("card-1", kotlinScaffold, "Add Hans", gate) }
        assertEquals(listOf("e: src/HansPlugin.kt:1:1 Expecting a top level declaration"), failure.result.errors)
        assertEquals(1, gate.compiled.size)
        val worktree = gate.compiled.single()
        assertEquals(worktree, repo.resolve("data/cockpit/worktrees/card-1").toAbsolutePath().normalize())
        // nothing committed on the branch, the file is still staged for the developer to look at
        assertEquals("base", git("log", "--format=%s", "cockpit/dialogue-hans-3105").trim())
        assertTrue(git("diff", "--cached", "--name-only", dir = worktree).contains("src/HansPlugin.kt"))
        assertTrue(Files.exists(worktree.resolve("src/HansPlugin.kt")))
    }

    @Test
    fun `a passing gate commits and the result travels with the commit`() {
        val gate = FakeGate(CompileResult(true, emptyList(), 50, ":game-plugins:compileKotlin"))
        val applied = workspace.apply("card-2", kotlinScaffold, "Add Hans", gate)
        assertEquals("Add Hans", git("log", "--format=%s", "cockpit/dialogue-hans-3105").lines().first())
        assertTrue(applied.compile.passed)
        assertEquals(50, applied.compile.durationMs)
        assertEquals(1, gate.compiled.size)
    }

    @Test
    fun `a scaffold without Kotlin files skips the gate`() {
        val gate = FakeGate(CompileResult(false, listOf("e: should never run"), 0, ":game-plugins:compileKotlin"))
        val applied = workspace.apply("card-3", jsonScaffold, "Add a door", gate)
        assertEquals(0, gate.compiled.size)
        assertTrue(applied.compile.passed)
        assertTrue(applied.compile.task.startsWith("skipped"))
        assertEquals("Add a door", git("log", "--format=%s", "cockpit/door-1").lines().first())
    }

    @Test
    fun `compiler output keeps the e lines and the failure block only`() {
        val output = """
            > Task :util:compileKotlin UP-TO-DATE
            > Task :game-plugins:compileKotlin FAILED
            e: file:///repo/game-plugins/src/main/kotlin/HansPlugin.kt:12:5 Unresolved reference: foo
            e: file:///repo/game-plugins/src/main/kotlin/HansPlugin.kt:13:9 Type mismatch: inferred type is String but Int was expected
            w: file:///repo/game-plugins/src/main/kotlin/HansPlugin.kt:3:1 Unused import

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':game-plugins:compileKotlin'.
            > Compilation error. See log for more details

            * Try:
            > Run with --stacktrace option to get the stack trace.

            BUILD FAILED in 41s
        """.trimIndent()
        val errors = CompileOutput.errors(output)
        assertEquals(
            listOf(
                "e: file:///repo/game-plugins/src/main/kotlin/HansPlugin.kt:12:5 Unresolved reference: foo",
                "e: file:///repo/game-plugins/src/main/kotlin/HansPlugin.kt:13:9 Type mismatch: inferred type is String but Int was expected",
                "FAILURE: Build failed with an exception.",
                "* What went wrong:",
                "Execution failed for task ':game-plugins:compileKotlin'.",
                "> Compilation error. See log for more details",
            ),
            errors,
        )
        assertFalse(errors.any { it.startsWith("w:") || it.contains("BUILD FAILED") || it.startsWith("* Try") })
        assertEquals(emptyList<String>(), CompileOutput.errors("BUILD SUCCESSFUL in 3s\n"))
    }
}
