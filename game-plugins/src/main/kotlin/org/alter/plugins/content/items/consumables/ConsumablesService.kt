package org.alter.plugins.content.items.consumables

import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.alter.rscm.RSCM.getRSCM
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Holds the parsed `data/cfg/consumables/consumables.json`, resolved to item ids. Loaded once at boot; a reload
 * (`::reloadconsumables`) parses off the game thread and the new table is swapped in on it, so a lookup never
 * sees a half-loaded table. Option bindings are made at plugin init, so a reload updates numbers for the items
 * already bound; new items need a restart.
 */
class ConsumablesService : Service {
    private val logger = KotlinLogging.logger {}

    lateinit var path: Path
        private set

    @Volatile
    private var table: ConsumablesTable = ConsumablesTable(emptyMap(), PrayerGear(emptyList(), emptyList()), emptyList())

    @Volatile
    private var byId: Map<Int, Consumable> = emptyMap()

    @Volatile
    var prayerGearWorn: IntArray = IntArray(0)
        private set

    @Volatile
    var prayerGearCarried: IntArray = IntArray(0)
        private set

    val consumables: List<Consumable> get() = table.consumables

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        path = Paths.get(serviceProperties.get("consumables") ?: "../data/cfg/consumables/consumables.json")
        swap(load())
        logger.info { "Loaded ${byId.size} consumables from $path." }
    }

    /** Parses and resolves the file. Safe to call off the game thread; the result is only published by [swap]. */
    fun load(): ConsumablesTable {
        val loaded = Consumables.load(path)
        // Resolve every name now so a typo fails the load, not a click.
        loaded.consumables.forEach { c ->
            getRSCM(c.item)
            c.replacement?.let { getRSCM(it) }
        }
        (loaded.prayerGear.worn + loaded.prayerGear.carried).forEach { getRSCM(it) }
        return loaded
    }

    /** Publishes [next]; call on the game thread. */
    fun swap(next: ConsumablesTable) {
        byId = next.consumables.associateBy { getRSCM(it.item) }
        prayerGearWorn = next.prayerGear.worn.map { getRSCM(it) }.toIntArray()
        prayerGearCarried = next.prayerGear.carried.map { getRSCM(it) }.toIntArray()
        table = next
    }

    fun lookup(itemId: Int): Consumable? = byId[itemId]
}
