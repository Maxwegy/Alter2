package org.alter.cockpit.inbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alter.cockpit.Json
import org.alter.cockpit.supervisor.GameServerSupervisor
import org.alter.cockpit.workorders.EnrichmentService

/**
 * GO on an `enrich.*` card: find the wiki page and run the enricher for the card's kind. The whole
 * [org.alter.cockpit.workorders.Enrichment] becomes the card's result (plus `pageUrl` for the card header);
 * the scaffold generators start from it.
 */
class EnrichExecutor(private val service: EnrichmentService) : ActionExecutor {
    override fun handles(kind: String): Boolean = kind.startsWith("enrich.")

    override suspend fun execute(action: InboxAction): Map<String, Any?> {
        val enrichment = service.enrich(action.params)
        @Suppress("UNCHECKED_CAST")
        val result = Json.mapper.convertValue(enrichment, Map::class.java) as Map<String, Any?>
        return result + ("pageUrl" to enrichment.page?.url)
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
