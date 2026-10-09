package org.alter.cockpit

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

class ScaffoldWorkspaceTests {
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
        Files.writeString(repo.resolve("data/cfg/list.json"), "[ {\"a\": 1} ]\n")
        Files.writeString(repo.resolve(".gitignore"), "/data/cockpit/\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
    }

    private val scaffold = Scaffold(
        "dialogue", "Hans dialogue", "cockpit/dialogue-hans-3105",
        listOf(
            ScaffoldFile("src/HansPlugin.kt", "class HansPlugin\n"),
            ScaffoldFile("data/cfg/list.json", "{\"b\": 2}", ScaffoldFile.JSON_APPEND),
            ScaffoldFile("data/cfg/doors.json", "{\"closed\": 1}", ScaffoldFile.JSON_APPEND, applyable = false),
        ),
    )

    @Test
    fun `preview writes applyable files to a worktree on a new branch and shows the diff`() {
        val preview = workspace.preview("card-1", scaffold)
        assertEquals("cockpit/dialogue-hans-3105", preview.branch)
        assertEquals(listOf(true, true, false), preview.files.map { it.applied })
        assertTrue(preview.diff.contains("+class HansPlugin"))
        assertTrue(preview.diff.contains("\"b\" : 2"))
        assertFalse(preview.diff.contains("closed"))
        // Same layout as the committed config files (pickpockets.json, single-doors.json), LF on every OS.
        assertEquals("[\n  {\n    \"a\" : 1\n  },\n  {\n    \"b\" : 2\n  }\n]\n", Files.readString(Path.of(preview.worktree).resolve("data/cfg/list.json")))
        // main is untouched
        assertEquals("hello\n", Files.readString(repo.resolve("README.md")))
        assertFalse(Files.exists(repo.resolve("src/HansPlugin.kt")))
        // previewing again is idempotent
        assertEquals(preview.diff, workspace.preview("card-1", scaffold).diff)
    }

    @Test
    fun `apply commits on the branch, discard removes worktree and branch`() {
        val applied = workspace.apply("card-1", scaffold, "Add Hans dialogue")
        assertEquals("cockpit/dialogue-hans-3105", applied.branch)
        assertTrue(git("log", "--format=%s", "cockpit/dialogue-hans-3105").lines().first() == "Add Hans dialogue")
        assertTrue(git("show", "--stat", "--format=", "cockpit/dialogue-hans-3105").contains("src/HansPlugin.kt"))
        assertThrows(IllegalStateException::class.java) { workspace.apply("card-1", scaffold, "again") }

        workspace.discard("card-1", applied.branch)
        assertFalse(Files.exists(Path.of(applied.worktree)))
        assertFalse(git("branch", "--list", "cockpit/dialogue-hans-3105").contains("cockpit"))
    }

    @Test
    fun `nothing applyable is refused`() {
        val onlyManual = scaffold.copy(files = scaffold.files.filter { !it.applyable })
        assertThrows(IllegalStateException::class.java) { workspace.apply("card-2", onlyManual, "x") }
    }
}
