package org.alter.cockpit.workorders

/** One `{{Infobox ...}}` template from a page: its name (e.g. `Infobox NPC`) and cleaned parameters. */
data class Infobox(val name: String, val params: Map<String, String>) {
    operator fun get(key: String): String? = params[key]?.takeIf { it.isNotBlank() }

    /** `|options = Talk-to, Age` as a list. */
    val options: List<String> get() = get("options")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
}

/**
 * Pulls infobox templates out of wikitext. Braces and brackets nest (`{{Map|...}}`, `[[a|b]]` inside values),
 * so parameters are split on `|` at nesting depth zero only. Values are cleaned to plain text.
 */
object InfoboxParser {
    fun parse(wikitext: String): List<Infobox> {
        val boxes = mutableListOf<Infobox>()
        var from = 0
        while (true) {
            val start = wikitext.indexOf("{{Infobox", from).takeIf { it >= 0 } ?: break
            val end = closing(wikitext, start) ?: break
            boxes += template(wikitext.substring(start + 2, end))
            from = end + 2
        }
        return boxes
    }

    fun first(wikitext: String, name: String): Infobox? = parse(wikitext).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Index of the `}}` that closes the `{{` at [start]. */
    private fun closing(text: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            when {
                text.startsWith("{{", i) -> { depth++; i += 2; continue }
                text.startsWith("}}", i) -> { depth--; if (depth == 0) return i; i += 2; continue }
            }
            i++
        }
        return null
    }

    private fun template(inner: String): Infobox {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var braces = 0
        var brackets = 0
        var i = 0
        while (i < inner.length) {
            when {
                inner.startsWith("{{", i) -> { braces++; current.append("{{"); i += 2; continue }
                inner.startsWith("}}", i) -> { braces--; current.append("}}"); i += 2; continue }
                inner.startsWith("[[", i) -> { brackets++; current.append("[["); i += 2; continue }
                inner.startsWith("]]", i) -> { brackets--; current.append("]]"); i += 2; continue }
                inner[i] == '|' && braces == 0 && brackets == 0 -> { parts += current.toString(); current.setLength(0); i++; continue }
            }
            current.append(inner[i])
            i++
        }
        parts += current.toString()
        val name = parts.first().trim()
        val params = parts.drop(1).mapNotNull { part ->
            val eq = part.indexOf('=').takeIf { it >= 0 } ?: return@mapNotNull null
            part.substring(0, eq).trim() to TranscriptParser.clean(part.substring(eq + 1).replace(Regex("""\{\{Map\|[^}]*}}"""), ""))
        }.toMap()
        return Infobox(name, params)
    }
}
