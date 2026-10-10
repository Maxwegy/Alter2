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
            ScaffoldFile.JSON_APPEND -> Files.writeString(target, appendToJsonArray(if (Files.exists(target)) Files.readString(target) else "[]", file.content, file.arrayKey))
            else -> Files.writeString(target, file.content)
        }
        return PreviewFile(file.path, file.mode, applied = true)
    }

    /**
     * Appends [element] before the array's closing bracket, keeping the file's own formatting so the diff is only the new entry.
     * With [arrayKey] the file is a JSON object and the element goes into its top-level `arrayKey` array (e.g. the
     * `consumables` list of consumables.json), indented like the array's first element.
     */
    internal fun appendToJsonArray(text: String, element: String, arrayKey: String? = null): String {
        if (arrayKey != null) return appendToNamedArray(text, element, arrayKey)
        val existing: List<Any?> = Json.mapper.readValue(text)
        val parsed = Json.mapper.readValue<Any?>(element)
        if (parsed in existing) return text // idempotent: previewing twice must not append twice
        val close = text.lastIndexOf(']').takeIf { it >= 0 } ?: throw IllegalStateException("Not a JSON array file")
        val head = text.substring(0, close).trimEnd()
        val pretty = Json.prettyLf.writeValueAsString(parsed).lines().joinToString("\n") { "  $it" }
        return head + (if (existing.isEmpty()) "\n" else ",\n") + pretty + "\n]\n"
    }

    private fun appendToNamedArray(text: String, element: String, arrayKey: String): String {
        val root = runCatching { Json.mapper.readValue<Map<String, Any?>>(text) }.getOrElse { throw IllegalStateException("Not a JSON object file") }
        val existing = root[arrayKey] as? List<*> ?: throw IllegalStateException("\"$arrayKey\" is not an array in this file")
        val parsed = Json.mapper.readValue<Any?>(element)
        if (parsed in existing) return text // idempotent: previewing twice must not append twice
        val open = JsonText.arrayOf(text, arrayKey) ?: throw IllegalStateException("No top-level \"$arrayKey\" array")
        val close = JsonText.matching(text, open)
        val first = (open + 1 until close).firstOrNull { !text[it].isWhitespace() }
        val indent = first?.takeIf { '\n' in text.substring(open, it) }?.let { JsonText.indentOf(text, it) }
            ?: (JsonText.indentOf(text, open) + "  ")
        val head = text.substring(0, close).trimEnd()
        val before = text.substring(head.length, close).takeIf { '\n' in it } ?: ("\n" + JsonText.indentOf(text, open))
        val pretty = Json.prettyLf.writeValueAsString(parsed).lines().joinToString("\n") { "$indent$it" }
        return head + (if (first == null) "\n" else ",\n") + pretty + before + text.substring(close)
    }

    /** Positions in JSON text, skipping string literals so a `]` or `"key"` inside a value is never mistaken for structure. */
    private object JsonText {
        /** Index of the `[` that opens the value of [key] in the root object, or null. */
        fun arrayOf(text: String, key: String): Int? {
            var depth = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '"' -> {
                        val end = stringEnd(text, i)
                        if (depth == 1 && text.substring(i + 1, end) == key) {
                            var j = end + 1
                            while (j < text.length && text[j].isWhitespace()) j++
                            if (j < text.length && text[j] == ':') {
                                j++
                                while (j < text.length && text[j].isWhitespace()) j++
                                if (j < text.length && text[j] == '[') return j
                            }
                        }
                        i = end
                    }
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                i++
            }
            return null
        }

        /** Index of the `]` closing the `[` at [open]. */
        fun matching(text: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '"' -> i = stringEnd(text, i)
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) return i }
                }
                i++
            }
            throw IllegalStateException("Unbalanced JSON array")
        }

        /** The whitespace that starts the line holding [index]. */
        fun indentOf(text: String, index: Int): String {
            val lineStart = text.lastIndexOf('\n', index - 1) + 1
            return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        }

        /** Index of the quote closing the string that opens at [start]. */
        private fun stringEnd(text: String, start: Int): Int {
            var i = start + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            throw IllegalStateException("Unterminated JSON string")
        }
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
