package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.objs.OpObjT
import org.alter.game.message.MessageHandler
import org.alter.game.model.EntityType
import org.alter.game.model.Tile
import org.alter.game.model.attr.INTERACTING_GROUNDITEM_ATTR
import org.alter.game.model.attr.INTERACTING_ITEM
import org.alter.game.model.attr.INTERACTING_ITEM_ID
import org.alter.game.model.attr.INTERACTING_ITEM_SLOT
import org.alter.game.model.attr.INTERACTING_OPT_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Entity
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.move.GroundItemRouteAction
import org.alter.game.plugin.InteractionType
import org.alter.game.plugin.UnhandledInteraction
import java.lang.ref.WeakReference

/**
 * An inventory item or a spell used on a ground item.
 *
 * This used to run the item-on-object path without setting its attributes, which threw a
 * NullPointerException or replayed a stale item-on-object target.
 */
class OpObjTHandler : MessageHandler<OpObjT> {
    override fun consume(
        client: Client,
        message: OpObjT,
    ) {
        val tile = Tile(message.x, message.z, client.tile.height)
        if (!tile.viewableFrom(client.tile, Player.TILE_VIEW_DISTANCE)) {
            return
        }
        if (!client.lock.canGroundItemInteract()) {
            return
        }
        log(
            client,
            "Use on ground item: item=%d, slot=%d, ground=%d, x=%d, y=%d",
            message.selectedObj,
            message.selectedSub,
            message.id,
            message.x,
            message.z,
        )
        val groundItem =
            client.world.chunks.getOrCreate(tile).getEntities<GroundItem>(tile, EntityType.GROUND_ITEM).firstOrNull {
                it.item == message.id && it.canBeViewedBy(client)
            } ?: return

        val usedItem = client.inventory.validated(message.selectedSub, message.selectedObj)
        if (usedItem != null) {
            client.attr[INTERACTING_ITEM] = WeakReference(usedItem)
            client.attr[INTERACTING_ITEM_ID] = usedItem.id
            client.attr[INTERACTING_ITEM_SLOT] = message.selectedSub
            client.attr[INTERACTING_OPT_ATTR] = GroundItemRouteAction.ITEM_ON_GROUND_ITEM_OPTION
            client.attr[INTERACTING_GROUNDITEM_ATTR] = WeakReference(groundItem)
            client.executePlugin(GroundItemRouteAction.walkPlugin)
            return
        }

        // Not an inventory item: a spell (e.g. telekinetic grab). No spell-on-ground-item plugins exist yet.
        client.writeMessage(Entity.NOTHING_INTERESTING_HAPPENS)
        client.world.plugins.executeUnhandledInteraction(
            client,
            UnhandledInteraction(
                InteractionType.SPELL_ON_GROUND,
                id = groundItem.item,
                component = (message.selectedInterfaceId shl 16) or message.selectedComponentId,
                tile = groundItem.tile,
            ),
        )
    }
}
