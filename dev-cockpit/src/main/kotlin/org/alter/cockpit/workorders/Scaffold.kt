package org.alter.cockpit.workorders

/** One file a scaffold writes. [applyable] is false while values are still missing: shown in the preview, not written. */
data class ScaffoldFile(
    /** Repository-relative path with forward slashes. */
    val path: String,
    val content: String,
    /** `create` (new file), `replace` (whole file) or `json-append` (append an element to a JSON array file). */
    val mode: String = CREATE,
    val applyable: Boolean = true,
    /** `json-append` into a JSON object file: the top-level key of the array to append to; null for a top-level array. */
    val arrayKey: String? = null,
) {
    companion object {
        const val CREATE = "create"
        const val REPLACE = "replace"
        const val JSON_APPEND = "json-append"
    }
}

/**
 * What applying a card would write: the files, the branch they go on, and what a human still has to fill in.
 * Generators only use facts from the enrichment; anything else becomes a TODO in the code and a [manual] note.
 */
data class Scaffold(
    val kind: String,
    val summary: String,
    val branch: String,
    val files: List<ScaffoldFile>,
    val manual: List<String> = emptyList(),
)
