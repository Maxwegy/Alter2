package dev.openrune.cache.tools.staging

import dev.openrune.cache.filestore.Cache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class GamevalTests {
    private val staged = Paths.get("../../data/cache-staging/241-2735")

    @Test
    fun `leading name is taken from plain and structured entries`() {
        assertEquals("shark", Gameval.name("shark".toByteArray()))
        assertEquals("quest", Gameval.name(byteArrayOf(1) + "quest".toByteArray() + byteArrayOf(0, 2) + "id".toByteArray()))
        assertEquals("100guide_eggs_overlay", Gameval.name("100guide_eggs_overlay".toByteArray() + byteArrayOf(0, 0, 8) + "100_q4".toByteArray()))
        assertEquals("", Gameval.name(ByteArray(0)))
    }

    @Test
    fun `the staged 241 cache names every item, npc and loc`() {
        assumeTrue("no staged 241 cache", Files.exists(staged.resolve("main_file_cache.idx255")))
        val cache = Cache.load(staged, false)
        try {
            assumeTrue(Gameval.isPresent(cache))
            val items = Gameval.read(cache, Gameval.Kind.OBJ)
            val npcs = Gameval.read(cache, Gameval.Kind.NPC)
            val locs = Gameval.read(cache, Gameval.Kind.LOC)
            assertEquals(34646, items.size)
            assertEquals(16631, npcs.size)
            assertEquals(62534, locs.size)
            assertEquals("shark", items[385])
            assertEquals("abyssal_whip", items[4151])
            assertEquals("hans", npcs[3105])
            assertEquals("tree", locs[1276])
            assertEquals("coalrock2", locs[11367])
            assertEquals("quest", Gameval.read(cache, Gameval.Kind.DBTABLE)[0])
            assertEquals("100guide_eggs_overlay", Gameval.read(cache, Gameval.Kind.INTERFACE)[0])
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a cache without index 24 has no gameval tables`() {
        val older = Paths.get("../../data/cache-staging/228-2043")
        assumeTrue("no staged 228 cache", Files.exists(older.resolve("main_file_cache.idx255")))
        val cache = Cache.load(older, false)
        try {
            assertFalse(Gameval.isPresent(cache))
            assertEquals(emptyMap<Int, String>(), Gameval.read(cache, Gameval.Kind.OBJ))
        } finally {
            cache.close()
        }
    }
}
