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

data class Applied(val branch: String, val commit: String, val worktree: String, val compile: CompileResult)

/**
 * Where scaffolds land: a git worktree per card under `data/cockpit/worktrees/`, on a `cockpit/...` branch
 * cut from [baseRef]. Preview writes the files and shows the staged diff; apply runs the [CompileGate] on the
 * staged worktree and commits only when it passes; discard throws the worktree and branch away. `main` is never
 * touched, and nothing is pushed.
 */
class ScaffoldWorkspace(repoRoot: Path, worktreesDir: Path, private val baseRef: String = "origin/main") {
    private val logger = KotlinLogging.logger {}

    // Absolute: git resolves relative paths against its own working directory, not ours.
    private val repoRoot: Path = repoRoot.toAbsolutePath().normalize()
    private val worktreesDir: Path = worktreesDir.toAbsolutePath().normalize()

    fun preview(cardId: String, scaffold: Scaffold): Preview {
        val dir = worktree(cardId, scaffold.branch)
        val files = scaffold.files.map { file -> write(dir, file) }
        git(dir, "add", "-A")
        return Preview(scaffold.branch, dir.toString(), files, git(dir, "diff", "--cached", "--no-color"))
    }

    /**
     * Stage, compile, commit. A failing [gate] throws [CompileFailed] and leaves the files staged in the
     * worktree, so the developer can look at them; nothing is committed. JSON-only scaffolds skip the gate:
     * the server validates those files at boot, the compiler never sees them.
     */
    fun apply(cardId: String, scaffold: Scaffold, message: String, gate: CompileGate = NoopCompileGate): Applied {
        val preview = preview(cardId, scaffold)
        if (preview.files.none { it.applied }) throw IllegalStateException("Nothing to apply: every file still needs values")
        val dir = Path.of(preview.worktree)
        if (git(dir, "diff", "--cached", "--name-only").isBlank()) throw IllegalStateException("Nothing to commit: the files already match")
        val compile = if (preview.files.any { it.applied && it.path.endsWith(".kt") }) gate.compile(dir) else CompileResult.skipped("no Kotlin files")
        if (!compile.passed) throw CompileFailed(compile)
        git(dir, "commit", "-q", "-m", message)
        val commit = git(dir, "rev-parse", "--short", "HEAD").trim()
        logger.info { "Applied card $cardId as $commit on ${scaffold.branch} (${compile.task}, ${compile.durationMs} ms)" }
        return Applied(scaffold.branch, commit, preview.worktree, compile)
    }

    fun discard(cardId: String, branch: String?) {
        val dir = worktreesDir.resolve(cardId)
        if (Files.exists(dir)) git(repoRoot, "worktree", "remove", "--force", dir.toString())
        git(repoRoot, "worktree", "prune")
        if (branch != null && branchExists(branch)) git(repoRoot, "branch", "-D", branch)
    }

    private fun worktree(cardId: String, branch: String): Path {
        val dir = worktreesDir.resolve(cardId)
        if (!Files.exists(dir.resolve(".git"))) {
            Files.createDirectories(worktreesDir)
            if (branchExists(branch)) git(repoRoot, "worktree", "add", dir.toString(), branch)
            else git(repoRoot, "worktree", "add", "-b", branch, dir.toString(), base())
        }
        // Never stage or commit anywhere but in the card's own worktree.
        val top = Path.of(git(dir, "rev-parse", "--show-toplevel").trim()).toAbsolutePath().normalize()
        check(top == dir) { "Worktree for card $cardId is $top, expected $dir" }
        return dir
    }

    /** [baseRef] when it exists (e.g. `origin/main`), else `main`: a checkout without a remote still works. */
    private fun base(): String = if (refExists(baseRef)) baseRef else "main"

    private fun branchExists(branch: String) = refExists("refs/heads/$branch")

    private fun refExists(ref: String) = runCatching { git(repoRoot, "rev-parse", "--verify", "--quiet", ref) }.isSuccess

    private fun write(dir: Path, file: ScaffoldFile): PreviewFile {
        if (!file.applyable) return PreviewFile(file.path, file.mode, applied = false, note = "needs values; not written")
        val target = dir.resolve(file.path)
        Files.createDirectories(target.parent)
        when (file.mode) {
            ScaffoldFile.JSON_APPEND -> Files.writeString(target, appendToJsonArray(if (Files.exists(target)) Files.readString(target) else "[]", file.content))
            else -> Files.writeString(target, file.content)
        }
        return PreviewFile(file.path, file.mode, applied = true)
    }

    /** Appends [element] before the array's closing bracket, keeping the file's own formatting so the diff is only the new entry. */
    internal fun appendToJsonArray(text: String, element: String): String {
        val existing: List<Any?> = Json.mapper.readValue(text)
        val parsed = Json.mapper.readValue<Any?>(element)
        if (parsed in existing) return text // idempotent: previewing twice must not append twice
        val close = text.lastIndexOf(']').takeIf { it >= 0 } ?: throw IllegalStateException("Not a JSON array file")
        val head = text.substring(0, close).trimEnd()
        val pretty = Json.prettyLf.writeValueAsString(parsed).lines().joinToString("\n") { "  $it" }
        return head + (if (existing.isEmpty()) "\n" else ",\n") + pretty + "\n]\n"
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
