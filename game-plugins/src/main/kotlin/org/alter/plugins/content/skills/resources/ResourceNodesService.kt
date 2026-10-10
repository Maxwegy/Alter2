package org.alter.plugins.content.skills.resources

import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.service.Service
import org.alter.rscm.RSCM.getRSCM
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Holds the parsed `data/cfg/resources/resource_nodes.json`, resolved to ids, and the runtime [registry]. Loaded
 * once at boot; a reload (`::reloadresources`) parses off the game thread and the new table is swapped in on it, so
 * a lookup never sees a half-loaded table. The registry is never replaced, so depleted nodes and running timers
 * survive a reload. Option bindings are made at plugin init: a reload updates numbers for the objects already
 * bound; new objects need a restart.
 */
class ResourceNodesService : Service {
    private val logger = KotlinLogging.logger {}

    /** One bound node object: its entry and the id of the object it turns into when depleted. */
    data class Bound(val node: NodeDef, val depletedId: Int)

    /** What the respawn queue needs to put a node back. */
    data class Restore(val id: Int, val type: Int, val rot: Int, val tile: Tile)

    var path: Path = DEFAULT_PATH
        private set

    @Volatile
    private var table: ResourceNodesTable = ResourceNodesTable.EMPTY

    @Volatile
    private var byObjectId: Map<Int, Bound> = emptyMap()

    /** Game-thread only; see [NodeStateRegistry]. */
    val registry = NodeStateRegistry<Restore>(HEARTBEAT_TICKS)

    val messages: ResourceMessages get() = table.messages

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) {
        path = Paths.get(serviceProperties.get("resource-nodes") ?: DEFAULT_PATH.toString())
        swap(load())
        logger.info { "Resource nodes: loaded ${table.loaded.size}, skipped ${table.skipped.size} (todo) from $path." }
    }

    /** Parses and resolves the file. Safe to call off the game thread; the result is only published by [swap]. */
    fun load(): ResourceNodesTable = load(path)

    fun load(file: Path): ResourceNodesTable {
        val loaded = ResourceNodeDefs.load(file)
        // Resolve every name now so a typo fails the load, not a click.
        loaded.skills.values.forEach { s ->
            s.tools.forEach { getRSCM(it.item) }
            s.preRoll?.table?.forEach { row -> row.item?.let { getRSCM(it) } }
        }
        loaded.nodes.forEach { n ->
            n.objects.forEach { getRSCM(it.obj); getRSCM(it.depleted) }
            n.objectsTodo.forEach { getRSCM(it.obj) }
            n.reward?.let { getRSCM(it) }
            n.tertiary.forEach { t -> t.item?.let { getRSCM(it) } }
        }
        return loaded
    }

    /** Publishes [next]; call on the game thread. */
    fun swap(next: ResourceNodesTable) {
        byObjectId = next.byObject.entries.associate { (name, pair) -> getRSCM(name) to Bound(pair.first, getRSCM(pair.second.depleted)) }
        table = next
    }

    fun lookup(objectId: Int): Bound? = byObjectId[objectId]

    fun skill(key: String): SkillDef? = table.skills[key]

    val loadedCount: Int get() = table.loaded.size

    val skippedCount: Int get() = table.skipped.size

    companion object {
        val DEFAULT_PATH: Path = Paths.get("../data/cfg/resources/resource_nodes.json")

        /**
         * A gatherer not seen for this many ticks stops counting towards a node's depletion timer. Engine safety
         * net for a loop that ended without a release, not game data; it must exceed the longest roll interval (8).
         */
        const val HEARTBEAT_TICKS = 16
    }
}
