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
        // The existing content keeps its formatting; only the new entry is added, so the diff is just that entry.
        assertEquals("[ {\"a\": 1},\n  {\n    \"b\" : 2\n  }\n]\n", Files.readString(Path.of(preview.worktree).resolve("data/cfg/list.json")))
        assertEquals(listOf("+  {", "+    \"b\" : 2", "+  }", "+]"), preview.diff.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.filter { "b" in it || it == "+  {" || it == "+  }" || it == "+]" })
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
    fun `a relative repo root and a missing origin still land in the right worktree`() {
        // The cockpit runs from game-server/ with repoRoot ".."; git must not resolve that against its own cwd.
        val cwd = Path.of("").toAbsolutePath()
        val relative = cwd.relativize(repo)
        val ws = ScaffoldWorkspace(relative, relative.resolve("data/cockpit/worktrees"), "origin/main")
        val preview = ws.preview("card-3", scaffold)
        assertEquals(repo.resolve("data/cockpit/worktrees/card-3").toAbsolutePath().normalize().toString(), preview.worktree)
        assertTrue(preview.diff.contains("+class HansPlugin"))
        // No origin/main here, so the branch is cut from main; the main checkout itself stays clean.
        assertEquals(git("rev-parse", "main").trim(), git("merge-base", "main", "cockpit/dialogue-hans-3105").trim())
        assertTrue(git("status", "--porcelain").isBlank())
    }

    @Test
    fun `nothing applyable is refused`() {
        val onlyManual = scaffold.copy(files = scaffold.files.filter { !it.applyable })
        assertThrows(IllegalStateException::class.java) { workspace.apply("card-2", onlyManual, "x") }
    }

    private val consumablesShaped = """
        {
          "schemaVersion": 1,
          "todo": [ "a ] b", "consumables" ],
          "consumables": [
            { "item": "item.shrimps", "kind": "food", "note": "x]" },
            { "item": "item.sardine", "kind": "food" }
          ],
          "after": [ 1 ]
        }
    """.trimIndent() + "\n"

    @Test
    fun `json-append with an array key adds only the new element, indented like the others, before that array's bracket`() {
        val out = workspace.appendToJsonArray(consumablesShaped, "{\"item\": \"item.anchovies\", \"kind\": \"food\"}", "consumables")
        val expected = consumablesShaped.replace(
            "    { \"item\": \"item.sardine\", \"kind\": \"food\" }\n  ],",
            "    { \"item\": \"item.sardine\", \"kind\": \"food\" },\n    {\n      \"item\" : \"item.anchovies\",\n      \"kind\" : \"food\"\n    }\n  ],",
        )
        assertEquals(expected, out)
        // A "]" or the key's name inside a string is not structure; the other arrays are untouched.
        assertTrue(out.contains("\"todo\": [ \"a ] b\", \"consumables\" ],"))
        assertTrue(out.endsWith("  \"after\": [ 1 ]\n}\n"))
        // idempotent: previewing twice must not append twice
        assertEquals(out, workspace.appendToJsonArray(out, "{\"kind\": \"food\", \"item\": \"item.anchovies\"}", "consumables"))
        assertThrows(IllegalStateException::class.java) { workspace.appendToJsonArray(consumablesShaped, "{}", "schemaVersion") }
        assertThrows(IllegalStateException::class.java) { workspace.appendToJsonArray("[]", "{}", "consumables") }
    }

    @Test
    fun `json-append into an empty named array and into a top-level array`() {
        assertEquals(
            "{\n  \"list\": [\n    {\n      \"b\" : 2\n    }\n  ]\n}\n",
            workspace.appendToJsonArray("{\n  \"list\": []\n}\n", "{\"b\": 2}", "list"),
        )
        // No array key: the top-level path is unchanged.
        assertEquals("[ {\"a\": 1},\n  {\n    \"b\" : 2\n  }\n]\n", workspace.appendToJsonArray("[ {\"a\": 1} ]\n", "{\"b\": 2}"))
        assertEquals("[\n  {\n    \"b\" : 2\n  }\n]\n", workspace.appendToJsonArray("[]", "{\"b\": 2}"))
    }
}
