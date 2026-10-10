package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.events.EventMouseClickV1
import net.rsprot.protocol.game.incoming.events.EventMouseClickV2
import org.alter.game.message.MessageHandler
import org.alter.game.model.entity.Client

/**
 * Mouse clicks are telemetry only; nothing acts on them yet. rsprot 241 splits the packet into V1 and V2.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class EventMouseClickHandler : MessageHandler<EventMouseClickV2> {
    override fun consume(
        client: Client,
        message: EventMouseClickV2,
    ) {
    }
}

class EventMouseClickV1Handler : MessageHandler<EventMouseClickV1> {
    override fun consume(
        client: Client,
        message: EventMouseClickV1,
    ) {
    }
}
