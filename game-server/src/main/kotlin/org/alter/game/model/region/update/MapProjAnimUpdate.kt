 package org.alter.game.model.region.update

 import net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2
import net.rsprot.protocol.internal.game.outgoing.info.CoordGrid
 import net.rsprot.protocol.message.ZoneProt
 import org.alter.game.model.entity.Projectile

 /**
 * Represents an update where a [Projectile] is spawned.
 *
 * @author Tom <rspsmods@gmail.com>
 */
 class MapProjAnimUpdate(
     override val entity: Projectile
 ) : EntityUpdate<Projectile>(entity) {

    override fun toMessage(): ZoneProt {
        return if (entity.targetPawn != null) {
            val targetIndex = if (entity.targetPawn.entityType.isNpc) entity.targetPawn.index + 1 else -(entity.targetPawn.index + 1)
            MapProjAnimV2(
                id = entity.gfx,
                startHeight = entity.startHeight,
                endHeight = entity.endHeight,
                startTime = entity.delay,
                endTime = entity.lifespan + entity.delay,
                angle = entity.angle,
                progress= entity.steepness,
                sourceIndex = 0,
                targetIndex = targetIndex,
                xInZone = (entity.tile.x and 0x7),
                zInZone = (entity.tile.z and 0x7),
                end = CoordGrid(entity.targetTile.height, entity.targetTile.x, entity.targetTile.z),
            )
        } else {
            //Note: identical to above except that the targetIndex is 0.
            MapProjAnimV2(
                id = entity.gfx,
                startHeight = entity.startHeight,
                endHeight = entity.endHeight,
                startTime = entity.delay,
                endTime = entity.lifespan + entity.delay,
                angle = entity.angle,
                progress= entity.steepness,
                sourceIndex = 0,
                targetIndex = 0,
                xInZone = (entity.tile.x and 0x7),
                zInZone = (entity.tile.z and 0x7),
                end = CoordGrid(entity.targetTile.height, entity.targetTile.x, entity.targetTile.z),
            )
        }
    }
 }
