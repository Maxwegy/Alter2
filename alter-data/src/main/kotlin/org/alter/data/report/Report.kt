package org.alter.data.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.io.AtomicFiles
import java.nio.file.Path
import java.time.Instant

/**
 * The shared report contract for every tool (wikiSync, cache dry-run, missing content):
 * a stable machine-readable `.json` for the Dev Cockpit next to a human `.md`.
 */
data class Report(
    val tool: String,
    val schemaVersion: Int = 1,
    val generatedAt: String = Instant.now().toString(),
    val summary: Map<String, Any> = emptyMap(),
    val sections: List<Section> = emptyList(),
) {
    data class Section(val title: String, val items: List<String>, val severity: Severity = Severity.INFO)

    enum class Severity { INFO, WARNING, ERROR }

    fun toMarkdown(): String = buildString {
        appendLine("# $tool report")
        appendLine()
        appendLine("Generated $generatedAt")
        appendLine()
        summary.forEach { (key, value) -> appendLine("- **$key:** $value") }
        sections.forEach { section ->
            appendLine()
            appendLine("## ${section.title} (${section.items.size})")
            appendLine()
            if (section.items.isEmpty()) appendLine("_None._")
            section.items.forEach { appendLine("- $it") }
        }
    }

    /** Writes `<dir>/<tool>-<timestamp>.json` and `.md`; returns the json path. */
    fun write(dir: Path): Path {
        val stem = "$tool-${generatedAt.replace(':', '-')}"
        val json = dir.resolve("$stem.json")
        AtomicFiles.write(json, mapper.writeValueAsBytes(this))
        AtomicFiles.writeText(dir.resolve("$stem.md"), toMarkdown())
        return json
    }

    private companion object {
        val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)
    }
}
