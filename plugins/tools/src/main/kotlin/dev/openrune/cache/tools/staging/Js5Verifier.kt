package dev.openrune.cache.tools.staging

import com.displee.cache.CacheLibrary
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * Checks every group of a disk cache against its index's reference table: the group must be readable and
 * the CRC32 of its container must match the reference table's CRC. This is what JS5 serves to clients, so a
 * cache that passes can be served without handing clients corrupt data.
 */
object Js5Verifier {
    data class IndexResult(val index: Int, val groups: Int, val missing: List<Int>, val crcMismatches: List<Int>, val error: String? = null)

    data class Result(val indices: List<IndexResult>, val error: String? = null) {
        val groups: Int get() = indices.sumOf { it.groups }
        val missing: Int get() = indices.sumOf { it.missing.size }
        val crcMismatches: Int get() = indices.sumOf { it.crcMismatches.size }
        val unreadable: List<String> get() = listOfNotNull(error) + indices.mapNotNull { r -> r.error?.let { "index ${r.index}: $it" } }
        val ok: Boolean get() = missing == 0 && crcMismatches == 0 && unreadable.isEmpty()
    }

    /** Never throws: a cache our library can't parse is reported as unreadable, not a crash. */
    fun verify(cacheDir: Path): Result {
        val library = try {
            CacheLibrary(cacheDir.toAbsolutePath().toString(), false, null)
        } catch (e: Exception) {
            return Result(emptyList(), "cannot open cache: ${e::class.simpleName}: ${e.message}")
        }
        try {
            return Result(
                library.indices().filter { it.id != 255 }.map { index -> try {
                    val missing = mutableListOf<Int>()
                    val mismatches = mutableListOf<Int>()
                    val ids = index.archiveIds()
                    ids.forEach { id ->
                        val expected = index.archive(id)?.crc
                        val data = index.readArchiveSector(id)?.data
                        if (data == null || expected == null) {
                            missing += id
                        } else if (!matches(data, expected)) {
                            mismatches += id
                        }
                    }
                    IndexResult(index.id, ids.size, missing, mismatches)
                } catch (e: Exception) {
                    IndexResult(index.id, 0, emptyList(), emptyList(), "${e::class.simpleName}: ${e.message}")
                } },
            )
        } finally {
            library.close()
        }
    }

    /** The reference CRC covers the container without its optional two-byte version trailer. */
    private fun matches(data: ByteArray, expected: Int): Boolean =
        crc(data, data.size) == expected || (data.size > 2 && crc(data, data.size - 2) == expected)

    private fun crc(data: ByteArray, length: Int): Int = CRC32().apply { update(data, 0, length) }.value.toInt()
}
