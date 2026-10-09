package org.alter.cockpit

/** Captured OSRS Wiki content under `src/test/resources/wiki/` (CC BY-NC-SA 3.0), fetched 2026-10-09. */
object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.getResourceAsStream("/wiki/$name")?.bufferedReader()?.readText() ?: error("No fixture $name")

    fun transcript(page: String) = text("Transcript_${page.replace(' ', '_').replace("(", "_").replace(")", "_")}.wikitext")
}
