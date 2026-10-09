package dev.openrune.cache.tools.staging

import dev.openrune.cache.filestore.Cache

/**
 * Index 24 of caches from revision 241 on holds the gameval name tables: one group per entity kind and one
 * file per id, whose content is the name the game's own scripts use (`shark`, `hans`, `coalrock2`). Most groups
 * are plain text; group 10 (dbtables) and 14 (interfaces) list a name followed by column/component names, and
 * only the leading name is taken from those.
 *
 * Group numbers were read off the 241 cache (OpenRS2 2735); kinds are named by what the files contain.
 */
object Gameval {
    const val INDEX = 24

    /** [rscm] is the committed `data/cfg/rscm/<rscm>.rscm` table that names the same ids, when we have one. */
    enum class Kind(val group: Int, val rscm: String? = null) {
        OBJ(0, "item"),
        NPC(1, "npc"),
        INV(2),
        VARP(3),
        VARBIT(4),
        LOC(6, "object"),
        SEQ(7),
        SPOTANIM(8),
        DBROW(9),
        DBTABLE(10),
        JINGLE(11),
        SPRITE(12),
        INTERFACE(14),
        VARC(15),
    }

    fun isPresent(cache: Cache): Boolean = cache.indexCount() > INDEX && cache.archives(INDEX).isNotEmpty()

    /** id → name for one kind; empty when the cache has no index 24 (revisions before 241). */
    fun read(cache: Cache, kind: Kind): Map<Int, String> {
        if (!isPresent(cache)) return emptyMap()
        val files = cache.files(INDEX, kind.group)
        val names = LinkedHashMap<Int, String>(files.size)
        for (file in files) {
            val data = cache.data(INDEX, kind.group, file) ?: continue
            names[file] = name(data)
        }
        return names
    }

    fun readAll(cache: Cache): Map<Kind, Map<Int, String>> = Kind.values().associateWith { read(cache, it) }

    /** The leading name: an optional control-byte prefix (group 10), then bytes up to the first control byte. */
    internal fun name(data: ByteArray): String {
        var start = 0
        if (data.isNotEmpty() && data[0] in 0 until 0x20) start = 1
        var end = start
        while (end < data.size && data[end] >= 0x20) end++
        return String(data, start, end - start, Charsets.ISO_8859_1)
    }
}
