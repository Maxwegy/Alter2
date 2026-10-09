package org.alter.data.snapshot

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import it.unimi.dsi.fastutil.ints.IntSets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** The read-only view of reference data that content depends on. Storage-agnostic by design. */
interface GameDataRepository {
    val schemaVersion: Int
    val cacheRevision: Int

    fun npcStats(npcId: Int): NpcEntry?

    /** Every NPC id with stats in the snapshot. */
    fun npcIds(): IntSet

    fun dropTables(npcId: Int): List<DropTable>

    fun itemStats(itemId: Int): ItemEntry?

    fun counts(): Map<String, Int>
}

/** Loads the committed JSON snapshot once into immutable maps (O(1) lookups by id). */
class SnapshotRepository private constructor(
    override val schemaVersion: Int,
    override val cacheRevision: Int,
    private val npcs: Int2ObjectMap<NpcEntry>,
    private val drops: Int2ObjectMap<List<DropTable>>,
    private val items: Int2ObjectMap<ItemEntry>,
) : GameDataRepository {
    override fun npcStats(npcId: Int): NpcEntry? = npcs.get(npcId)

    override fun npcIds(): IntSet = IntSets.unmodifiable(IntOpenHashSet(npcs.keys))

    override fun dropTables(npcId: Int): List<DropTable> = drops.get(npcId) ?: emptyList()

    override fun itemStats(itemId: Int): ItemEntry? = items.get(itemId)

    override fun counts() = mapOf("npcIds" to npcs.size, "npcIdsWithDrops" to drops.size, "items" to items.size)

    sealed interface LoadResult {
        data object Missing : LoadResult

        data class SchemaMismatch(val found: Int, val expected: Int) : LoadResult

        data class Loaded(val repository: SnapshotRepository) : LoadResult
    }

    companion object {
        private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val EMPTY = SnapshotRepository(SNAPSHOT_SCHEMA_VERSION, -1, Int2ObjectMaps.emptyMap(), Int2ObjectMaps.emptyMap(), Int2ObjectMaps.emptyMap())

        fun load(dir: Path): LoadResult {
            val manifestFile = dir.resolve(SnapshotWriter.MANIFEST)
            if (!Files.exists(manifestFile)) return LoadResult.Missing
            val manifest = mapper.readValue<Manifest>(manifestFile.toFile())
            if (manifest.schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
                return LoadResult.SchemaMismatch(manifest.schemaVersion, SNAPSHOT_SCHEMA_VERSION)
            }

            val npcs = Int2ObjectOpenHashMap<NpcEntry>()
            jsonFiles(dir.resolve("npcs")).forEach { file ->
                mapper.readValue<NpcPage>(file.toFile()).versions.forEach { entry -> entry.ids.forEach { npcs.putIfAbsent(it, entry) } }
            }
            val drops = Int2ObjectOpenHashMap<MutableList<DropTable>>()
            jsonFiles(dir.resolve("drops")).forEach { file ->
                mapper.readValue<DropPage>(file.toFile()).tables.forEach { table ->
                    table.npcIds.forEach { drops.getOrPut(it) { mutableListOf() } += table }
                }
            }
            val items = Int2ObjectOpenHashMap<ItemEntry>()
            dir.resolve("items.json").takeIf(Files::exists)?.let { file ->
                mapper.readValue<ItemsFile>(file.toFile()).items.forEach { items.put(it.id, it) }
            }
            @Suppress("UNCHECKED_CAST")
            return LoadResult.Loaded(
                SnapshotRepository(
                    manifest.schemaVersion,
                    manifest.cacheRevision,
                    Int2ObjectMaps.unmodifiable(npcs),
                    Int2ObjectMaps.unmodifiable(drops as Int2ObjectMap<List<DropTable>>),
                    Int2ObjectMaps.unmodifiable(items),
                ),
            )
        }

        private fun jsonFiles(dir: Path): List<Path> {
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.list(dir).use { stream -> stream.filter { it.extension == "json" }.sorted().toList() }
        }
    }
}
