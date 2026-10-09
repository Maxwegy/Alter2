package org.alter.plugins.content.infrastructure.drops

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.alter.data.drops.Fraction
import org.alter.data.drops.LootMath
import org.alter.data.snapshot.Drop
import org.alter.data.snapshot.DropTable
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.weightedTableBuilder.Loot
import org.alter.game.model.weightedTableBuilder.LootTable
import org.alter.game.model.weightedTableBuilder.TableType
import org.alter.game.service.Service
import org.alter.plugins.content.infrastructure.GameDataService
import org.alter.rscm.RSCM
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Drop tables for NPCs that don't define their own: override files first, then the wiki snapshot.
 * Converted to the server's [LootTable]s once per NPC id and cached. Game thread only.
 */
class DropDataService(
    private val data: GameDataService,
    private val overridesDir: Path,
) : Service {
    private val logger = KotlinLogging.logger {}
    private val converted = Int2ObjectOpenHashMap<List<LootTable>>()
    private var overrides: Map<Int, List<DropTable>> = emptyMap()

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        overrides = loadOverrides()
        logger.info { "Drop overrides: ${overrides.size} NPC ids." }
    }

    /** The source tables for [npcId] (override or snapshot), before conversion. */
    fun sourceTables(npcId: Int): List<DropTable> = overrides[npcId] ?: data.repository.dropTables(npcId)

    fun isOverridden(npcId: Int) = npcId in overrides

    fun tables(npcId: Int): List<LootTable> = converted.getOrPut(npcId) { sourceTables(npcId).flatMap(::toLootTables) }

    /** Drops cached conversions and re-reads overrides, e.g. after a wiki reload. Game thread only. */
    fun reload() {
        converted.clear()
        overrides = loadOverrides()
    }

    private fun toLootTables(table: DropTable): List<LootTable> {
        val result = mutableListOf<LootTable>()
        if (table.always.isNotEmpty()) {
            result += LootTable(TableType.ALWAYS, 0, table.always.map { loot(it, weight = 0) }.toCollection(LinkedHashSet()))
        }
        table.main.groupBy { it.rolls }.forEach { (rolls, drops) ->
            val weighed = LootMath.weigh(merge(drops).map { (drop, chance) -> drop to chance })
            val loot = weighed.weights.map { (drop, weight) -> loot(drop, weight) }.toCollection(LinkedHashSet())
            result += LootTable(TableType.MAIN, weighed.total, loot, rolls)
        }
        table.tertiary.forEach { drop ->
            val (weight, total) = LootMath.single(Fraction.fromPair(drop.chance!!))
            result += LootTable(TableType.TERTIARY, total, linkedSetOf(loot(drop, weight)), drop.rolls)
        }
        return result
    }

    /** Identical item/quantity lines (e.g. the wiki listing coins twice at one quantity) share one slot. */
    private fun merge(drops: List<Drop>): List<Pair<Drop, Fraction>> =
        drops.groupBy { Triple(it.item, it.min, it.max) }.map { (_, same) ->
            same.first() to same.map { Fraction.fromPair(it.chance!!) }.reduce { a, b -> runCatching { a + b }.getOrElse { Fraction(Math.round((a.value + b.value) * LootMath.SCALE), LootMath.SCALE.toLong()) } }
        }

    private fun loot(drop: Drop, weight: Int) = Loot(item = drop.item, min = drop.min, max = drop.max, weight = weight)

    private fun loadOverrides(): Map<Int, List<DropTable>> {
        if (!Files.isDirectory(overridesDir)) return emptyMap()
        val files = Files.list(overridesDir).use { stream -> stream.filter { it.extension in setOf("yml", "yaml") }.sorted().toList() }
        val result = HashMap<Int, MutableList<DropTable>>()
        files.forEach { file ->
            try {
                val override = yaml.readValue<DropOverride>(file.toFile())
                val npcIds = override.npcs.map(RSCM::getRSCM)
                val table = DropTable(
                    source = "override:${file.fileName}",
                    npcIds = npcIds,
                    always = override.always.map { it.toDrop() },
                    main = override.main.map { it.toDrop() },
                    tertiary = override.tertiary.map { it.toDrop() },
                )
                npcIds.forEach { result.getOrPut(it, ::mutableListOf) += table }
            } catch (e: Exception) {
                logger.error(e) { "Skipping drop override $file" }
            }
        }
        return result
    }

    /** Whole-table replacement for the listed NPCs. Items by RSCM name, chances as `[numerator, denominator]`. */
    data class DropOverride(
        val npcs: List<String> = emptyList(),
        val always: List<OverrideDrop> = emptyList(),
        val main: List<OverrideDrop> = emptyList(),
        val tertiary: List<OverrideDrop> = emptyList(),
    )

    data class OverrideDrop(val item: String, val min: Int = 1, val max: Int = min, val chance: List<Long>? = null, val rolls: Int = 1) {
        fun toDrop() = Drop(item = RSCM.getRSCM(item), name = item, min = min, max = max, chance = chance, rolls = rolls)
    }

    private companion object {
        val yaml = YAMLMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
