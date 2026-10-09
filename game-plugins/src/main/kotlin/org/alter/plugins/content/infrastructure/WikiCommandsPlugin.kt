package org.alter.plugins.content.infrastructure

import gg.rsmod.util.BuildInfo
import kotlinx.coroutines.launch
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.data.cache.CacheManagerView
import org.alter.data.config.DataPaths
import org.alter.data.config.InfraConfig
import org.alter.data.http.WikiHttpClient
import org.alter.data.snapshot.SnapshotRepository
import org.alter.data.wiki.RawCache
import org.alter.data.wiki.WikiBucketClient
import org.alter.data.wiki.WikiSync
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.service.GameService
import org.alter.plugins.content.infrastructure.drops.DropDataService
import org.alter.plugins.content.infrastructure.items.ItemStatsService
import org.alter.plugins.content.infrastructure.npcs.NpcDataService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `::wikisync [--offline|--refresh]` (dev only): runs the same sync as `./gradlew :alter-data:wikiSync` on the
 * IO scope, then swaps the new snapshot in on the game thread. The tick never waits on the network.
 * Live NPCs pick up new stats when they respawn; drops and item stats apply immediately.
 */
class WikiCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val running = AtomicBoolean(false)

    init {
        onCommand("wikisync", Privilege.DEV_POWER, description = "Re-sync wiki data and reload it live") {
            val args = player.getCommandArgs()
            val options = WikiSync.Options(offline = "--offline" in args, refresh = "--refresh" in args)
            startSync(player, options)
        }
    }

    private fun startSync(player: Player, options: WikiSync.Options) {
        val infra = world.getService(InfrastructureService::class.java) ?: return player.message("Data infrastructure is not running.")
        val gameService = world.getService(GameService::class.java) ?: return player.message("No game service.")
        if (!running.compareAndSet(false, true)) return player.message("A wiki sync is already running.")
        player.message("Wiki sync started${if (options.offline) " (offline)" else ""}; you'll be told when it's done.")

        infra.io.scope.launch {
            val outcome = try {
                val paths = DataPaths.default()
                val config = InfraConfig.load(paths.config)
                val http = if (options.offline) null else WikiHttpClient(config.wiki)
                try {
                    val cache = CacheManagerView(BuildInfo.REVISION, paths.dataDir.resolve("cfg/rscm"))
                    val result = WikiSync(http?.let(::WikiBucketClient), RawCache(paths.wikiCache), cache, config.wiki, paths.wikiSnapshot).run(options)
                    result.report.write(paths.reports)
                    when (result) {
                        is WikiSync.Result.Rejected -> "Wiki sync rejected: ${result.reason}" to null
                        is WikiSync.Result.Written -> {
                            val loaded = SnapshotRepository.load(paths.wikiSnapshot) as? SnapshotRepository.LoadResult.Loaded
                            "Wiki sync done: ${result.result.written.size} files changed, ${result.result.removed.size} removed." to loaded?.repository
                        }
                    }
                } finally {
                    http?.close()
                }
            } catch (e: Exception) {
                "Wiki sync failed: ${e.message}" to null
            }

            gameService.submitGameThreadJob {
                running.set(false)
                val (message, repository) = outcome
                if (repository != null) {
                    world.getService(GameDataService::class.java)?.swap(repository)
                    val npcs = world.getService(NpcDataService::class.java)?.reload(world)
                    world.getService(DropDataService::class.java)?.reload()
                    world.getService(ItemStatsService::class.java)?.apply()
                    player.message("$message Reloaded ${npcs?.registered ?: 0} NPC defs; drops and item stats are live, NPC stats apply on respawn.")
                } else {
                    player.message(message)
                }
            }
        }
    }
}
