package org.alter.plugins.content.infrastructure

import gg.rsmod.util.BuildInfo
import gg.rsmod.util.ServerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.snapshot.GameDataRepository
import org.alter.data.snapshot.SnapshotRepository
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Loads the committed wiki snapshot once at boot, before NPCs spawn. A missing snapshot is allowed (content
 * falls back to defaults); a snapshot with a different schema version is fatal, because loading it would
 * silently drop data.
 */
class GameDataService(private val snapshotDir: Path) : Service {
    private val logger = KotlinLogging.logger {}

    /** Swapped as a whole (on the game thread) on hot reload; read it once per operation. */
    @Volatile
    var repository: GameDataRepository = SnapshotRepository.EMPTY
        private set

    override fun init(server: Server, world: World, serviceProperties: ServerProperties) = load()

    /** Loads the snapshot (also usable without a running world, e.g. in tests). */
    fun load() {
        val started = System.currentTimeMillis()
        when (val result = SnapshotRepository.load(snapshotDir)) {
            SnapshotRepository.LoadResult.Missing ->
                logger.warn { "No wiki snapshot in ${snapshotDir.toAbsolutePath().normalize()}; NPC stats and drops use defaults. Run ./gradlew :alter-data:wikiSync." }
            is SnapshotRepository.LoadResult.SchemaMismatch -> {
                logger.error { "Wiki snapshot schema ${result.found} does not match this build (${result.expected}). Re-run ./gradlew :alter-data:wikiSync." }
                exitProcess(1)
            }
            is SnapshotRepository.LoadResult.Loaded -> {
                repository = result.repository
                if (result.repository.cacheRevision != BuildInfo.REVISION) {
                    logger.warn { "Wiki snapshot was validated against cache ${result.repository.cacheRevision}, not ${BuildInfo.REVISION}. Re-run wikiSync." }
                }
                logger.info { "Loaded wiki snapshot ${result.repository.counts()} in ${System.currentTimeMillis() - started}ms." }
            }
        }
    }

    /** Replaces the repository; call on the game thread. */
    fun swap(next: GameDataRepository) {
        repository = next
    }
}
