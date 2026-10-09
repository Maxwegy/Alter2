package dev.openrune.cache.filestore

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.buffer.BufferReader
import dev.openrune.cache.filestore.definition.decoder.EntityOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The config opcodes added between revisions 229 and 241. The synthetic tests always run; the staged-cache test
 * runs only where `data/cache-staging/241-2735` exists and loads every definition kind through
 * [CacheManager.init], which is exactly what the server does at boot.
 */
class Revision241DecodeTests {
    private fun bytes(build: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).build() }.toByteArray()

    private fun DataOutputStream.writeCString(s: String) { write(s.toByteArray(Charsets.ISO_8859_1)); writeByte(0) }

    @Test
    fun `sub-ops read index then (subId + 1, text) pairs until 0`() {
        val reader = BufferReader(bytes { writeByte(2); writeByte(1); writeCString("Talk-to"); writeByte(3); writeCString("Trade"); writeByte(0) })
        val subops = mutableMapOf<Int, MutableMap<Int, String>>()
        EntityOps.readSubOps(reader, subops)
        assertEquals(mapOf(2 to mapOf(0 to "Talk-to", 2 to "Trade")), subops)
        assertEquals(0, reader.readableBytes())
    }

    @Test
    fun `conditional ops carry the varp, varbit and range`() {
        val op = EntityOps.readConditionalOp(BufferReader(bytes { writeByte(1); writeShort(300); writeShort(65535); writeInt(5); writeInt(9); writeCString("Enter") }))
        assertEquals(1, op.index); assertEquals(-1, op.subId); assertEquals(300, op.varp); assertEquals(65535, op.varbit)
        assertEquals(5, op.min); assertEquals(9, op.max); assertEquals("Enter", op.text)

        val sub = EntityOps.readConditionalSubOp(BufferReader(bytes { writeByte(4); writeShort(7); writeShort(65535); writeShort(1234); writeInt(-1); writeInt(0); writeCString("Quick-start") }))
        assertEquals(4, sub.index); assertEquals(7, sub.subId); assertEquals(65535, sub.varp); assertEquals(1234, sub.varbit)
        assertEquals(-1, sub.min); assertEquals(0, sub.max); assertEquals("Quick-start", sub.text)
    }

    @Test
    fun `the staged 241 cache decodes every definition kind without a decoder failure`() {
        val staged = Paths.get("../../data/cache-staging/241-2735")
        assumeTrue("no staged 241 cache", Files.exists(staged.resolve("main_file_cache.idx255")))
        CacheManager.init(staged, 241)
        // Counts match the gameval name tables in index 24 (one file per id).
        assertEquals(34646, CacheManager.getItems().size)
        assertEquals(16631, CacheManager.getNpcs().size)
        assertEquals(62534, CacheManager.getObjects().size)
        assertEquals("Shark", CacheManager.getItem(385).name)
        assertEquals("Hans", CacheManager.getNpc(3105).name)
        assertEquals("Tree", CacheManager.getObject(1276).name)
        assertTrue(CacheManager.getAnims().isNotEmpty())
        assertTrue(CacheManager.getEnums().isNotEmpty())
        assertTrue(CacheManager.getStructs().isNotEmpty())
        assertTrue(CacheManager.getVarbits().isNotEmpty())
    }
}
