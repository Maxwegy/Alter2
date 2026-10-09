package org.alter.cockpit.workorders

/**
 * A wiki `Transcript:` page as a tree. Lines are what someone says; options are the choices the player gets;
 * actions are what the wiki marks with `{{tact}}` (end, a shop opening, "same as above", a continuation).
 * `type` is serialized so the UI and the scaffold generators can switch on it.
 */
sealed class Node {
    abstract val type: String

    data class Line(val speaker: String, val text: String) : Node() {
        override val type get() = "line"
        val player: Boolean get() = speaker.equals("Player", ignoreCase = true)
    }

    data class Options(val prompt: String?, val options: List<Option>) : Node() {
        override val type get() = "options"
    }

    /** `{{trandom}}`: the NPC picks one of these at random. */
    data class Random(val options: List<Option>) : Node() {
        override val type get() = "random"
    }

    data class Action(val kind: String, val target: String?, val text: String) : Node() {
        override val type get() = "action"

        companion object {
            const val END = "end"
            const val OPENS = "opens"
            const val ABOVE = "above"
            const val CONTINUE = "continue"
            const val OTHER = "other"
        }
    }

    data class Condition(val text: String, val body: List<Node>) : Node() {
        override val type get() = "condition"
    }

    /** Stage directions (`{{qact}}`) and anything the parser doesn't understand. */
    data class Note(val text: String, val body: List<Node> = emptyList()) : Node() {
        override val type get() = "note"
    }
}

data class Option(val label: String, val body: List<Node>)

data class Section(
    val title: String,
    /** The enclosing `==` section for a `===` subsection. */
    val parent: String?,
    val body: List<Node>,
    /** True when a heading in the section's path mentions a quest state (before/after/during ...). */
    val questDependent: Boolean,
)

data class Transcript(val sections: List<Section>, val incomplete: Boolean) {
    /** Every `{{tact|opens=...}}` target, e.g. shop names. */
    fun opens(): List<String> = sections.flatMap { it.body.flatMap(::opens) }.distinct()

    private fun opens(node: Node): List<String> = when (node) {
        is Node.Action -> listOfNotNull(node.target.takeIf { node.kind == Node.Action.OPENS })
        is Node.Options -> node.options.flatMap { it.body.flatMap(::opens) }
        is Node.Random -> node.options.flatMap { it.body.flatMap(::opens) }
        is Node.Condition -> node.body.flatMap(::opens)
        is Node.Note -> node.body.flatMap(::opens)
        is Node.Line -> emptyList()
    }
}

/**
 * Parses the wiki's transcript markup, verified against live pages: `==` headings, `*` bullets whose depth is
 * the tree depth, `'''Speaker:''' text`, and the templates `{{tselect}}`, `{{topt}}`, `{{trandom}}`, `{{tact}}`,
 * `{{tcond}}`, `{{qact}}`, `{{overhead}}`, `{{sic}}`. Unknown markup becomes a [Node.Note], never an error.
 */
object TranscriptParser {
    private val heading = Regex("""^(=+)\s*(.+?)\s*=+$""")
    private val bullet = Regex("""^(\*+)\s*(.*)$""")
    private val speaker = Regex("""^'''(.+?):?'''\s*(.*)$""")
    private val template = Regex("""^\{\{(\w+)\|?(.*)}}$""")
    private val link = Regex("""\[\[([^\]|]+)""")
    private val questHint = Regex("""(?i)\b(before|after|during|completion|completed|started|quest)\b""")

    private class Item(val depth: Int, val text: String, val children: MutableList<Item> = mutableListOf())

    fun parse(wikitext: String): Transcript {
        val sections = mutableListOf<Section>()
        val titles = mutableMapOf<Int, String>()
        var title = "Standard dialogue"
        var parent: String? = null
        var items = mutableListOf<Item>()
        val stack = ArrayDeque<Item>()

        fun flush() {
            if (items.isNotEmpty()) sections += Section(title, parent, convert(items), questDependent(listOfNotNull(parent, title)))
            items = mutableListOf()
            stack.clear()
        }

        for (raw in wikitext.lines()) {
            val line = raw.trim()
            val h = heading.matchEntire(line)
            if (h != null) {
                flush()
                val depth = h.groupValues[1].length
                titles[depth] = clean(h.groupValues[2])
                titles.keys.filter { it > depth }.forEach(titles::remove)
                title = titles[depth]!!
                parent = if (depth > 2) titles[depth - 1] else null
                continue
            }
            val b = bullet.matchEntire(line) ?: continue
            val item = Item(b.groupValues[1].length, b.groupValues[2])
            while (stack.isNotEmpty() && stack.last().depth >= item.depth) stack.removeLast()
            (stack.lastOrNull()?.children ?: items) += item
            stack.addLast(item)
        }
        flush()
        return Transcript(sections, "{{Incomplete" in wikitext)
    }

    private fun questDependent(path: List<String>) = path.any { !it.equals("Standard dialogue", ignoreCase = true) && questHint.containsMatchIn(it) }

    private fun convert(items: List<Item>): List<Node> {
        val out = mutableListOf<Node>()
        var group: MutableList<Option>? = null
        var prompt: String? = null
        var random = false
        fun close() {
            group?.let { out += if (random) Node.Random(it) else Node.Options(prompt, it) }
            group = null
            prompt = null
            random = false
        }
        for (item in items) {
            val m = template.matchEntire(item.text)
            when (m?.groupValues?.get(1)) {
                "topt" -> (group ?: mutableListOf<Option>().also { group = it }) += Option(clean(m.groupValues[2]), convert(item.children))
                "tselect" -> { close(); prompt = clean(m.groupValues[2]) }
                "trandom" -> { close(); random = true }
                else -> { close(); out += node(item, m) }
            }
        }
        close()
        return out
    }

    private fun node(item: Item, m: MatchResult?): List<Node> {
        val arg = m?.groupValues?.get(2).orEmpty()
        return when (m?.groupValues?.get(1)) {
            "tact" -> listOf(action(arg)) + convert(item.children)
            "tcond" -> listOf(Node.Condition(clean(arg), convert(item.children)))
            "qact" -> listOf(Node.Note(clean(arg), convert(item.children)))
            else -> {
                val s = speaker.matchEntire(item.text)
                if (s != null) listOf(Node.Line(clean(s.groupValues[1]), clean(s.groupValues[2]))) + convert(item.children)
                else listOf(Node.Note(clean(item.text), convert(item.children)))
            }
        }
    }

    private fun action(arg: String): Node.Action = when {
        arg == "end" -> Node.Action(Node.Action.END, null, "The conversation ends.")
        arg.startsWith("opens=") -> clean(arg.removePrefix("opens=")).trimEnd('.').let { Node.Action(Node.Action.OPENS, it, "Opens $it.") }
        arg == "above" -> Node.Action(Node.Action.ABOVE, null, "Same as above.")
        arg.startsWith("Continues in", ignoreCase = true) -> Node.Action(Node.Action.CONTINUE, link.find(arg)?.groupValues?.get(1), clean(arg))
        else -> Node.Action(Node.Action.OTHER, null, clean(arg))
    }

    /** Wikitext to plain text: links to their label, inline templates to their text or nothing, tags and quotes gone. */
    fun clean(s: String): String = s
        .replace(Regex("""\{\{overhead\|(.*?)}}"""), "$1")
        .replace(Regex("""\{\{sic(\|[^}]*)?}}"""), "")
        .replace(Regex("""\[\[(?:[^\]|]*\|)?([^\]]*)]]"""), "$1")
        .replace(Regex("""\{\{[^{}]*}}"""), "")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("'''", "")
        .replace("''", "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
