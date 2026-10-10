package org.alter.data.spawns

/**
 * Raw wikitext captured once from https://oldschool.runescape.wiki/ (`?action=raw`, 2026-10-10) under
 * `wiki/spawns/`. Man.wikitext is the dev-cockpit fixture of the same page.
 */
internal object Fixtures {
    fun page(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/wiki/spawns/$name.wikitext")) { "missing fixture $name" }.readText().replace("\r\n", "\n")

    val hans get() = page("Hans")
    val shopKeeper get() = page("Shop_keeper_Lumbridge")
    val duke get() = page("Duke_Horacio")
    val townCrier get() = page("Town_Crier")
    val man get() = page("Man")
    val mapDoc get() = page("Template_Map_doc")
}
