package org.alter.cockpit.workorders

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The check that runs between staging a scaffold and committing it: the card's worktree must compile.
 * Implementations are blocking and slow (a Gradle daemon may have to start), so [WorkOrders] runs them off
 * the request thread.
 */
interface CompileGate {
    val task: String

    /** Runs [task] in [worktree]; a scaffold may ask for its own task (e.g. a data test) instead of the default. */
    fun compile(worktree: Path, task: String = this.task): CompileResult
}

data class CompileResult(val passed: Boolean, val errors: List<String>, val durationMs: Long, val task: String) {
    companion object {
        fun skipped(reason: String) = CompileResult(passed = true, errors = emptyList(), durationMs = 0, task = "skipped: $reason")
    }
}

/** Thrown by [ScaffoldWorkspace.apply] when the gate fails; the files stay staged and nothing is committed. */
class CompileFailed(val result: CompileResult) : RuntimeException("${result.task} failed: ${result.errors.firstOrNull() ?: "no compiler output"}")

/** `workorders.compileGate: false`: every scaffold passes. */
object NoopCompileGate : CompileGate {
    override val task = "disabled"

    override fun compile(worktree: Path, task: String) = CompileResult.skipped("compile gate disabled")
}

/**
 * Runs the worktree's own Gradle wrapper on [task] (by default `:game-plugins:compileKotlin`, the module every
 * scaffold writes to) with the worktree as the working directory, so only that card's files are compiled.
 * `JAVA_HOME` and the rest of the environment are inherited from the cockpit.
 */
class GradleCompileGate(
    override val task: String = DEFAULT_TASK,
    private val timeout: Duration = Duration.ofMinutes(15),
) : CompileGate {
    private val logger = KotlinLogging.logger {}

    override fun compile(worktree: Path, task: String): CompileResult {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = worktree.toAbsolutePath().normalize().resolve(if (windows) "gradlew.bat" else "gradlew")
        if (!Files.exists(wrapper)) return CompileResult(false, listOf("No ${wrapper.fileName} in $worktree"), elapsed(), task)
        val command = command(wrapper, windows, task)
        logger.info { "Compile gate: ${command.joinToString(" ")} in $worktree" }
        val process = ProcessBuilder(command).directory(worktree.toFile()).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(name = "compile-gate-output", isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { synchronized(output) { output.appendLine(it) } }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(5_000)
            val errors = listOf("$task timed out after ${timeout.toMinutes()} minutes") + CompileOutput.errors(synchronized(output) { output.toString() })
            return CompileResult(false, errors, elapsed(), task)
        }
        reader.join(5_000)
        val text = synchronized(output) { output.toString() }
        val passed = process.exitValue() == 0
        val errors = if (passed) emptyList() else CompileOutput.errors(text).ifEmpty { listOf("$task exited with code ${process.exitValue()}") }
        logger.info { "Compile gate ${if (passed) "passed" else "failed"} in ${elapsed()} ms (${errors.size} error lines)" }
        return CompileResult(passed, errors, elapsed(), task)
    }

    companion object {
        const val DEFAULT_TASK = ":game-plugins:compileKotlin"

        /**
         * The wrapper by absolute path: with `NoDefaultCurrentDirectoryInExePath` set (Git for Windows and other
         * hardened shells set it), `cmd /c gradlew.bat` will not run a program from the working directory.
         * [task] may carry arguments (`:game-plugins:test --tests *ConsumablesDataTests`), so it is split on whitespace.
         */
        internal fun command(wrapper: Path, windows: Boolean, task: String): List<String> =
            (if (windows) listOf("cmd", "/c", wrapper.toString()) else listOf(wrapper.toString())) + listOf("--console=plain", "-q") +
                task.trim().split(Regex("""\s+"""))
    }
}

/** Pulls the lines a developer needs out of Gradle's console output. */
object CompileOutput {
    /**
     * Kotlin compiler errors (`e: file.kt:12:5 message`) and Gradle's failure block (`FAILURE:` up to `* Try:`),
     * in order, without duplicates.
     */
    fun errors(output: String): List<String> {
        val kept = ArrayList<String>()
        var inFailure = false
        for (raw in output.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("e: ") -> kept += line
                line.startsWith("FAILURE:") -> { inFailure = true; kept += line }
                inFailure && line.startsWith("* Try:") -> inFailure = false
                inFailure && line.isNotBlank() -> kept += line
            }
        }
        return kept.distinct()
    }
}
