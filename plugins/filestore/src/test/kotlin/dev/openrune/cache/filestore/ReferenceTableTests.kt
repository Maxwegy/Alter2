package dev.openrune.cache.filestore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Reference tables (idx255) carry optional per-group blocks selected by a flags byte. Caches from build 241 on
 * set flag 0x4 (compressed + uncompressed lengths) on every index; a parser that only knows 0x1 and 0x2 reads
 * file counts out of the lengths block and runs off the end of the table.
 */
class ReferenceTableTests {
    private class Stub : ReadOnlyCache(1) {
        override fun sector(index: Int, archive: Int): ByteArray? = null
        override fun data(index: Int, archive: Int, file: Int, xtea: IntArray?): ByteArray? = null
    }

    /** Two groups (ids 3 and 5) with files [0, 4] and [7], protocol 7, in the client's block order. */
    private fun table(flags: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(7)
        out.writeInt(1791285254) // revision
        out.writeByte(flags)
        out.writeShort(2) // group count (big smart, small form)
        out.writeShort(3); out.writeShort(2) // ids as deltas: 3, 5
        if (flags and 0x1 != 0) repeat(2) { out.writeInt(0x11111111) } // name hashes
        repeat(2) { out.writeInt(0x22222222) } // CRCs
        if (flags and 0x8 != 0) repeat(2) { out.writeInt(0x33333333) } // uncompressed CRCs
        if (flags and 0x2 != 0) repeat(2) { out.write(ByteArray(64) { 0x44 }) } // whirlpool digests
        if (flags and 0x4 != 0) repeat(2) { out.writeInt(0x55555555); out.writeInt(0x66666666) } // lengths
        repeat(2) { out.writeInt(0x77777777) } // versions
        out.writeShort(2); out.writeShort(1) // file counts
        out.writeShort(0); out.writeShort(4) // group 3: files 0, 4
        out.writeShort(7) // group 5: file 7
        return bytes.toByteArray()
    }

    private fun assertParsed(flags: Int) {
        val cache = Stub()
        val highest = cache.readReferenceTable(0, table(flags))
        assertEquals("flags 0x${flags.toString(16)}", 5, highest)
        assertArrayEquals(intArrayOf(3, 5), cache.archives(0))
        assertArrayEquals(intArrayOf(0, 4), cache.files(0, 3))
        assertArrayEquals(intArrayOf(7), cache.files(0, 5))
        assertEquals(1, cache.fileCount(0, 5))
    }

    @Test
    fun `tables without optional blocks still parse`() = assertParsed(0x0)

    @Test
    fun `names and whirlpool blocks are skipped`() {
        assertParsed(0x1)
        assertParsed(0x2)
        assertParsed(0x3)
    }

    @Test
    fun `lengths and uncompressed crc blocks are skipped`() {
        assertParsed(0x4)
        assertParsed(0x8)
        assertParsed(0xF)
    }

    @Test
    fun `the staged build 241 cache loads every index`() {
        val staged = Paths.get("../../data/cache-staging/241-2735")
        assumeTrue("no staged 241 cache", Files.exists(staged.resolve("main_file_cache.idx255")))
        val cache = FileCache(staged.toString())
        try {
            assertEquals(25, cache.indexCount())
            assertEquals(41, cache.archives(2).size)
            assertEquals(200304, cache.archives(2).sumOf { cache.fileCount(2, it) })
            assertEquals(54, cache.archives(19).size)
            assertEquals(207, cache.archives(19).sumOf { cache.fileCount(19, it) })
        } finally {
            cache.close()
        }
    }
}
