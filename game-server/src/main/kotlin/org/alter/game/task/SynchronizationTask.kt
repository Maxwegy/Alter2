package org.alter.game.task

import net.rsprot.protocol.game.outgoing.map.RebuildRegionV2
import net.rsprot.protocol.game.outgoing.map.RebuildNormalV2
import net.rsprot.protocol.game.outgoing.info.util.safeReleaseOrThrow
import net.rsprot.protocol.game.outgoing.info.util.onSuccess
import net.rsprot.protocol.game.outgoing.info.util.onFailure
import net.rsprot.protocol.game.outgoing.info.util.isEmpty
import net.rsprot.protocol.game.outgoing.info.npcinfo.SetNpcUpdateOrigin
import net.rsprot.protocol.game.outgoing.info.util.BuildArea
import net.rsprot.protocol.game.outgoing.map.RebuildNormal
import net.rsprot.protocol.game.outgoing.map.RebuildRegion
import net.rsprot.protocol.game.outgoing.map.util.RebuildRegionZone
import net.rsprot.protocol.game.outgoing.worldentity.SetActiveWorld
import org.alter.game.model.Coordinate
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.instance.InstancedChunkSet
import org.alter.game.model.region.Chunk
import org.alter.game.service.GameService

/**
 * A [GameTask] that is responsible for sending [org.alter.game.model.entity.Pawn]
 * data to [org.alter.game.model.entity.Pawn]s.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class SequentialSynchronizationTask : GameTask {
    override fun execute(
        world: World,
        service: GameService,
    ) {
        val worldPlayers = world.players
        val worldNpcs = world.npcs

        worldPlayers.forEach(Player::playerCoordCycleTask)

        // rsprot 241: one update builds world entity, player and npc infos for every player.
        world.network.infoProtocols.update()

        world.players.forEach {
            /**
             * Non-human [org.alter.game.model.entity.Player]s do not need this
             * to send any synchronization data to their game-client as they do
             * not have one.
             */
            if (it.entityType.isHumanControlled && it.initiated) {
                it.writeInfoPackets()
            }
        }

        for (n in worldNpcs.entries) {
            n?.npcPostSynchronizationTask()
        }
        worldPlayers.forEach(Player::postCycle)
    }
}

fun Player.playerPreSynchronizationTask() {
    val pawn = this
    pawn.movementQueue.cycle()
    val last = pawn.lastKnownRegionBase
    val current = pawn.tile
    if (last == null || shouldRebuildRegion(last, current)) {
        val regionX = ((current.x shr 3) - (Chunk.MAX_VIEWPORT shr 4)) shl 3
        val regionZ = ((current.z shr 3) - (Chunk.MAX_VIEWPORT shr 4)) shl 3
        // @TODO UpdateZoneFullFollowsMessage
        pawn.lastKnownRegionBase = Coordinate(regionX, regionZ, current.height)
        val instance = pawn.world.instanceAllocator.getMap(current)
        val rebuildMessage =
            when {
                instance != null -> {
                    RebuildRegionV2(
                        current.x shr 3,
                        current.z shr 3,
                        true,
                        object : RebuildRegionV2.RebuildRegionZoneProvider {
                            override fun provide(
                                zoneX: Int,
                                zoneZ: Int,
                                level: Int,
                            ): RebuildRegionZone? {
                                val coord = InstancedChunkSet.getCoordinates(zoneX, zoneZ, level)
                                val chunk = instance.chunks.values[coord] ?: return null
                                return RebuildRegionZone(
                                    chunk.zoneX,
                                    chunk.zoneZ,
                                    chunk.height,
                                    chunk.rot,
                                )
                            }
                        },
                    )
                }
                else -> RebuildNormalV2(current.x shr 3, current.z shr 3, -1)
            }
        pawn.buildArea = BuildArea((current.x ushr 3) - 6, (current.z ushr 3) - 6)
        pawn.infos.updateRootBuildAreaCenteredOnPlayer(current.x, current.z)
        pawn.write(rebuildMessage)
    }
}

private fun shouldRebuildRegion(
    old: Coordinate,
    new: Tile,
): Boolean {
    val dx = new.x - old.x
    val dz = new.z - old.z

    return dx <= Player.NORMAL_VIEW_DISTANCE || dx >= Chunk.MAX_VIEWPORT - Player.NORMAL_VIEW_DISTANCE - 1 ||
        dz <= Player.NORMAL_VIEW_DISTANCE || dz >= Chunk.MAX_VIEWPORT - Player.NORMAL_VIEW_DISTANCE - 1
}

fun Npc.npcPreSynchronizationTask() {
    val pawn = this
    pawn.movementQueue.cycle()
}

fun Npc.npcPostSynchronizationTask() {
    val pawn = this
    val oldTile = pawn.lastTile
    val moved = oldTile == null || !oldTile.sameAs(pawn.tile)

    if (moved) {
        pawn.lastTile = pawn.tile
    }
    pawn.moved = false
    pawn.steps = null
}

/**
 * Updates the coords for all players within the rsprot library. This is run after processing to properly account for
 * displacement effects [dspear, etc]
 */
fun Player.playerCoordCycleTask() {
    this.infos.updateRootCoord(this.tile.height, this.tile.x, this.tile.z)
}

/**
 * Writes this cycle's info packets in the order rsprot documents: active world, npc update origin, world entity
 * info, player info, npc info (omitted when empty, released instead), then any dynamic worlds, then the root
 * world again so client-side pathfinding is on the root world.
 */
fun Player.writeInfoPackets() {
    val packets = this.infos.getPackets()
    val root = packets.rootWorldInfoPackets
    write(root.activeWorld)
    write(root.npcUpdateOrigin)
    root.worldEntityInfo.onSuccess { write(it) }.onFailure { logger.error(it) { "World entity info failed for $username" } }
    root.playerInfo.onSuccess { write(it) }.onFailure { logger.error(it) { "Player info failed for $username" } }
    if (root.npcInfo.isEmpty()) {
        root.npcInfo.safeReleaseOrThrow()
    } else {
        root.npcInfo.onSuccess { write(it) }.onFailure { logger.error(it) { "Npc info failed for $username" } }
    }
    for (worldPackets in packets.activeWorlds) {
        write(worldPackets.activeWorld)
        if (worldPackets.npcInfo.isEmpty()) {
            worldPackets.npcInfo.safeReleaseOrThrow()
        } else {
            write(worldPackets.npcUpdateOrigin)
            worldPackets.npcInfo.onSuccess { write(it) }.onFailure { logger.error(it) { "Npc info (world ${worldPackets.worldId}) failed for $username" } }
        }
    }
    if (packets.activeWorlds.isNotEmpty()) {
        write(root.activeWorld)
    }
}

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
