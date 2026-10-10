package org.alter.data.spawns

import java.security.MessageDigest

/**
 * Stable spawn entry ids, derived from content so that every tool computes the same id for the same spawn.
 *
 * - Wiki entries: `"w" + sha256("$page\n$npc\n$x\n$z\n$height")` (first 12 hex digits), where [page] is the full
 *   wiki URL and x/z/height the nominal tile the `{{Map}}` feature gives. Regenerating the same feature gives the
 *   same id, so a manual or edited entry that carries it claims that feature for good.
 * - Every other entry: `"m" + sha256("$npc\n$x\n$z\n$height\n$salt")`, minted once and never recomputed: the salt is
 *   [MIGRATION_SALT] for entries converted from schema 1 and the edit's `at` instant for entries added in game.
 *
 * Ids match [PATTERN] and are unique across all region files.
 */
object SpawnIds {
    val PATTERN = Regex("^[wm][0-9a-f]{12}$")
    const val WIKI_PREFIX = "w"
    const val MINTED_PREFIX = "m"

    /** The salt of the ids minted for the schema-1 manual entries. */
    const val MIGRATION_SALT = "schema1"

    private const val HEX_DIGITS = 12

    fun wiki(page: String, npc: String, x: Int, z: Int, height: Int): String = WIKI_PREFIX + hash("$page\n$npc\n$x\n$z\n$height")

    fun minted(npc: String, x: Int, z: Int, height: Int, salt: String): String = MINTED_PREFIX + hash("$npc\n$x\n$z\n$height\n$salt")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HEX_DIGITS)
    }
}
