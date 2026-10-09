package org.alter.cockpit.inbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alter.cockpit.supervisor.GameServerSupervisor
import org.alter.cockpit.wiki.PageResolver

/**
 * GO on an `enrich.*` card: find the wiki page for the thing the card is about. The result (`pageUrl`) is what
 * the work-order engine starts from; until that lands, the card is "done" when the page is known.
 */
class ResolveWikiPageExecutor(private val resolver: PageResolver) : ActionExecutor {
    override fun handles(kind: String): Boolean = kind.startsWith("enrich.")

    override suspend fun execute(action: InboxAction): Map<String, Any?> {
        val type = action.params["lookupType"] as? String ?: throw IllegalStateException("No wiki lookup for ${action.params["type"]} cards yet")
        val id = (action.params["id"] as? Number)?.toInt() ?: throw IllegalStateException("Card has no id")
        val pageUrl = withContext(Dispatchers.IO) { resolver.resolve(type, id) }
            ?: throw IllegalStateException("The wiki has no $type page for id $id (${action.params["name"] ?: "unnamed"})")
        return mapOf("pageUrl" to pageUrl, "lookupUrl" to resolver.lookupUrl(type, id))
    }
}

/** GO on a `server.start` card (offered after a crash): start the game server. */
class ServerStartExecutor(private val supervisor: GameServerSupervisor) : ActionExecutor {
    override fun handles(kind: String): Boolean = kind == "server.start"

    override suspend fun execute(action: InboxAction): Map<String, Any?> {
        val started = withContext(Dispatchers.IO) { supervisor.start() }
        if (!started) throw IllegalStateException("The server is already running")
        return mapOf("started" to true)
    }
}
