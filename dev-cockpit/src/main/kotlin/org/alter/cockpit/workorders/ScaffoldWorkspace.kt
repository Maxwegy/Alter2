package org.alter.cockpit.workorders

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.cockpit.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class PreviewFile(val path: String, val mode: String, val applied: Boolean, val note: String? = null)

data class Preview(val branch: String, val worktree: String, val files: List<PreviewFile>, val diff: String)

data class Applied(val branch: String, val commit: String, val worktree: String)

/**
 * Where scaffolds land: a git worktree per card under `data/cockpit/worktrees/`, on a `cockpit/...` branch
 * cut from [baseRef]. Preview writes the files and shows the staged diff; apply commits them; discard throws
 * the worktree and branch away. `main` is never touched, and nothing is pushed.
 */
class ScaffoldWorkspace(private val repoRoot: Path, private val worktreesDir: Path, private val baseRef: String = "main") {
    private val logger = KotlinLogging.logger {}

    fun preview(cardId: String, scaffold: Scaffold): Preview {
        val dir = worktree(cardId, scaffold.branch)
        val files = scaffold.files.map { file -> write(dir, file) }
        git(dir, "add", "-A")
        return Preview(scaffold.branch, dir.toString(), files, git(dir, "diff", "--cached", "--no-color"))
    }

    fun apply(cardId: String, scaffold: Scaffold, message: String): Applied {
        val preview = preview(cardId, scaffold)
        if (preview.files.none { it.applied }) throw IllegalStateException("Nothing to apply: every file still needs values")
        if (git(Path.of(preview.worktree), "diff", "--cached", "--name-only").isBlank()) throw IllegalStateException("Nothing to commit: the files already match")
        git(Path.of(preview.worktree), "commit", "-q", "-m", message)
        val commit = git(Path.of(preview.worktree), "rev-parse", "--short", "HEAD").trim()
        logger.info { "Applied card $cardId as $commit on ${scaffold.branch}" }
        return Applied(scaffold.branch, commit, preview.worktree)
    }

    fun discard(cardId: String, branch: String?) {
        val dir = worktreesDir.resolve(cardId)
        if (Files.exists(dir)) git(repoRoot, "worktree", "remove", "--force", dir.toString())
        git(repoRoot, "worktree", "prune")
        if (branch != null && branchExists(branch)) git(repoRoot, "branch", "-D", branch)
    }

    private fun worktree(cardId: String, branch: String): Path {
        val dir = worktreesDir.resolve(cardId)
        if (Files.exists(dir.resolve(".git"))) return dir
        Files.createDirectories(worktreesDir)
        if (branchExists(branch)) git(repoRoot, "worktree", "add", dir.toString(), branch)
        else git(repoRoot, "worktree", "add", "-b", branch, dir.toString(), baseRef)
        return dir
    }

    private fun branchExists(branch: String) = runCatching { git(repoRoot, "rev-parse", "--verify", "--quiet", "refs/heads/$branch") }.isSuccess

    private fun write(dir: Path, file: ScaffoldFile): PreviewFile {
        if (!file.applyable) return PreviewFile(file.path, file.mode, applied = false, note = "needs values; not written")
        val target = dir.resolve(file.path)
        Files.createDirectories(target.parent)
        when (file.mode) {
            ScaffoldFile.JSON_APPEND -> {
                val existing: MutableList<Any?> = if (Files.exists(target)) Json.mapper.readValue(target.toFile()) else mutableListOf()
                val element = Json.mapper.readValue<Any?>(file.content)
                // Idempotent: previewing twice must not append twice.
                if (element !in existing) existing += element
                Files.writeString(target, Json.prettyLf.writeValueAsString(existing) + "\n")
            }
            else -> Files.writeString(target, file.content)
        }
        return PreviewFile(file.path, file.mode, applied = true)
    }

    private fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("git ${args.joinToString(" ")} timed out")
        }
        if (process.exitValue() != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: ${output.trim()}")
        return output
    }

    companion object {
        fun isGitRepo(root: Path): Boolean = Files.exists(root.resolve(".git")) || File(root.toFile(), ".git").exists()
    }
}
