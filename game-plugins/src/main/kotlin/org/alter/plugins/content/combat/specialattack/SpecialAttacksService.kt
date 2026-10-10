package org.alter.plugins.content.combat.specialattack

import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.alter.rscm.RSCM.getRSCM
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Holds the parsed `data/cfg/combat/special_attacks.json`, resolved to item ids. Loaded once at boot; a reload
 * (`::reloadspecials`) parses off the game thread and the new table is swapped in on it, so a lookup never sees a
 * half-loaded table. Entries with a todo are resolved (so their names stay valid) but not looked up.
 */
class SpecialAttacksService : Service {
    private val logger = KotlinLogging.logger {}

    lateinit var path: Path
        private set

    @Volatile
    private var table: SpecialAttacksTable = SpecialAttacksTable.EMPTY

    @Volatile
    private var byId: Map<Int, SpecialDef> = emptyMap()

    val energy: EnergySettings get() = table.energy

    val messages: SpecialMessages get() = table.messages

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        path = Paths.get(serviceProperties.get("special-attacks") ?: "../data/cfg/combat/special_attacks.json")
        swap(load())
        logger.info { "Special attacks: loaded ${table.loaded.size}, skipped ${table.skipped.size} (todo)." }
    }

    /** Parses and resolves the file. Safe to call off the game thread; the result is only published by [swap]. */
    fun load(): SpecialAttacksTable {
        val loaded = SpecialAttackDefs.load(path)
        // Resolve every name now so a typo fails the load, not a click.
        loaded.specials.forEach { def -> def.items.forEach { getRSCM(it) } }
        return loaded
    }

    /** Publishes [next]; call on the game thread. */
    fun swap(next: SpecialAttacksTable) {
        byId = next.byItem.mapKeys { (name, _) -> getRSCM(name) }
        table = next
    }

    val loadedCount: Int get() = table.loaded.size

    val skippedCount: Int get() = table.skipped.size

    fun lookup(itemId: Int): SpecialDef? = byId[itemId]
}
