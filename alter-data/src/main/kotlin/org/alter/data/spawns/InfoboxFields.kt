package org.alter.data.spawns

/**
 * Template-level wikitext helpers shared by [InfoboxFields] and [MapTemplateParser]: brace matching and the
 * MediaWiki parameter split (`|` only at nesting depth zero, so `{{Map|...}}` and `[[a|b]]` stay inside one
 * value). Values are kept raw, never cleaned.
 */
internal object Wikitext {
    private val COMMENT = Regex("""<!--.*?(-->|$)""", RegexOption.DOT_MATCHES_ALL)
    private val NOWIKI = Regex("""<nowiki>.*?</nowiki>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Drops HTML comments and `<nowiki>` blocks, which MediaWiki never expands as templates. */
    fun stripInert(text: String): String = text.replace(COMMENT, "").replace(NOWIKI, "")

    /** Index of the `}}` that closes the `{{` at [start], or null when it is never closed. */
    fun closing(text: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            when {
                text.startsWith("{{", i) -> {
                    depth++
                    i += 2
                    continue
                }
                text.startsWith("}}", i) -> {
                    depth--
                    if (depth == 0) return i
                    i += 2
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Every `{{...}}` in [text] whose name satisfies [nameMatches], outermost first, as the text inside the braces. */
    fun templates(text: String, nameMatches: (String) -> Boolean): List<IntRange> {
        val found = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            val start = text.indexOf("{{", i).takeIf { it >= 0 } ?: break
            val end = closing(text, start)
            if (end == null) {
                i = start + 2
                continue
            }
            val name = name(text.substring(start + 2, end))
            if (nameMatches(name)) {
                found += start..end + 1
                i = end + 2
            } else {
                i = start + 2
            }
        }
        return found
    }

    /** The template name: the text before the first depth-zero `|`, trimmed. */
    fun name(inner: String): String = split(inner).first().trim()

    /** Splits a template's inner text on depth-zero `|`; the first part is the name. */
    fun split(inner: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var braces = 0
        var brackets = 0
        var i = 0
        while (i < inner.length) {
            when {
                inner.startsWith("{{", i) -> {
                    braces++
                    current.append("{{")
                    i += 2
                    continue
                }
                inner.startsWith("}}", i) -> {
                    braces--
                    current.append("}}")
                    i += 2
                    continue
                }
                inner.startsWith("[[", i) -> {
                    brackets++
                    current.append("[[")
                    i += 2
                    continue
                }
                inner.startsWith("]]", i) -> {
                    brackets--
                    current.append("]]")
                    i += 2
                    continue
                }
                inner[i] == '|' && braces <= 0 && brackets <= 0 -> {
                    parts += current.toString()
                    current.setLength(0)
                    i++
                    continue
                }
            }
            current.append(inner[i])
            i++
        }
        parts += current.toString()
        return parts
    }

    /** Index of the first `=` outside nested templates and links, or -1 (a positional parameter). */
    fun equalsAtDepthZero(part: String): Int {
        var braces = 0
        var brackets = 0
        var i = 0
        while (i < part.length) {
            when {
                part.startsWith("{{", i) -> {
                    braces++
                    i += 2
                    continue
                }
                part.startsWith("}}", i) -> {
                    braces--
                    i += 2
                    continue
                }
                part.startsWith("[[", i) -> {
                    brackets++
                    i += 2
                    continue
                }
                part.startsWith("]]", i) -> {
                    brackets--
                    i += 2
                    continue
                }
                part[i] == '=' && braces == 0 && brackets == 0 -> return i
            }
            i++
        }
        return -1
    }

    /** MediaWiki compares template names with the first letter case-insensitive. */
    fun sameName(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && a[0].equals(b[0], ignoreCase = true) && a.substring(1) == b.substring(1)
}

/**
 * The `{{Infobox NPC}}` and `{{Infobox Monster}}` parameters a spawn needs, read straight from wikitext.
 *
 * Template:Infobox_NPC/doc (https://oldschool.runescape.wiki/w/Template:Infobox_NPC/doc): `map` holds an
 * image, a `{{Map}}` mapframe or "No"; `id` is the game id. Multi-version infoboxes number their parameters
 * (`version1`, `map1`, `id1`, ...) and unnumbered parameters are shared by every version, so each version
 * pairs `mapN ?: map` with `idN ?: id`. Values stay raw (a `{{Map|...}}` is kept verbatim).
 */
object InfoboxFields {
    val INFOBOX_NAMES = listOf("Infobox NPC", "Infobox Monster")

    /** One infobox: its template name and its named parameters, values trimmed but otherwise raw. */
    data class Infobox(val name: String, val params: Map<String, String>) {
        operator fun get(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
    }

    /** One version's spawn inputs: [version] is null for a box without versions, [label] its `versionN` text. */
    data class VersionPair(val version: Int?, val label: String?, val map: String?, val id: String?)

    sealed interface Id {
        data class Single(val id: Int) : Id

        data object Missing : Id

        data class Ambiguous(val ids: List<String>) : Id

        data class Invalid(val raw: String) : Id
    }

    private val VERSION_KEY = Regex("""version(\d+)""")

    /** Every Infobox NPC / Infobox Monster on the page, in page order (including ones nested in a switch infobox). */
    fun infoboxes(wikitext: String): List<Infobox> {
        val text = Wikitext.stripInert(wikitext)
        return Wikitext.templates(text) { name -> INFOBOX_NAMES.any { Wikitext.sameName(it, name.replace('_', ' ')) } }
            .map { range -> parse(text.substring(range.first + 2, range.last - 1)) }
    }

    /** Parses the inside of `{{...}}`: depth-zero split, `key = value` with the value trimmed and kept raw. */
    fun parse(inner: String): Infobox {
        val parts = Wikitext.split(inner)
        val params = LinkedHashMap<String, String>()
        parts.drop(1).forEach { part ->
            val eq = Wikitext.equalsAtDepthZero(part)
            if (eq >= 0) params[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        return Infobox(parts.first().trim(), params)
    }

    /** The (map, id) pair of every version, by version number; one pair when the box has no versions. */
    fun pairs(box: Infobox): List<VersionPair> {
        val versions = box.params.keys.mapNotNull { VERSION_KEY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.distinct().sorted()
        if (versions.isEmpty()) return listOf(VersionPair(null, null, box["map"], box["id"]))
        return versions.map { n -> VersionPair(n, box["version$n"], box["map$n"] ?: box["map"], box["id$n"] ?: box["id"]) }
    }

    /** The id of one version: exactly one integer, or why not. */
    fun id(raw: String?): Id {
        val parts = raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        return when {
            parts.isEmpty() -> Id.Missing
            parts.size > 1 -> Id.Ambiguous(parts)
            else -> parts.single().toIntOrNull()?.takeIf { it >= 0 }?.let(Id::Single) ?: Id.Invalid(raw!!.trim())
        }
    }
}
