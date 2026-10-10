package org.alter.plugins.content.combat.autocast

import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.alter.rscm.RSCM.getRSCM
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Holds the parsed `data/cfg/combat/autocast.json` with its item names resolved to ids. Loaded once at boot; a
 * lookup is a map read on the game thread.
 */
class AutocastService : Service {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var table: AutocastTable = AutocastTable.EMPTY
        private set

    @Volatile
    private var groupsById: Map<Int, List<String>> = emptyMap()

    @Volatile
    private var menuValues: Map<String, Int> = emptyMap()

    fun pathOrDefault(properties: ServerProperties? = null): Path =
        Paths.get(properties?.get<String>("autocast") ?: "../data/cfg/combat/autocast.json")

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        swap(AutocastDefs.load(pathOrDefault(serviceProperties)))
        val t = table
        val ui = if (t.ui.bound) "UI bound" else "UI unbound (TODO)"
        logger.info {
            "Autocast: loaded ${t.loadedGroups.size} spell groups, skipped ${t.groups.size - t.loadedGroups.size} (todo), " +
                "${t.byItem.size} weapons by item; $ui."
        }
    }

    /** Resolves every name now, so a typo fails the boot rather than a click. */
    fun swap(next: AutocastTable) {
        groupsById = next.byItem.mapKeys { (name, _) -> getRSCM(name) }
        menuValues = next.groups.associate { it.name to (it.menuKey?.let { key -> getRSCM(key) } ?: -1) }
        table = next
    }

    /** The loaded groups the weapon may autocast (empty for no weapon). */
    fun groupsFor(itemId: Int?, weaponType: Int): List<SpellGroup> {
        if (itemId == null) return emptyList()
        val names = (table.byWeaponType[weaponType].orEmpty() + groupsById[itemId].orEmpty()).toSet()
        return table.loadedGroups.filter { it.name in names }
    }

    /** The menu varp value that lists [group] in interface 201 (-1 when its menuKey is null). */
    fun menuValue(group: SpellGroup): Int = menuValues[group.name] ?: -1
}
