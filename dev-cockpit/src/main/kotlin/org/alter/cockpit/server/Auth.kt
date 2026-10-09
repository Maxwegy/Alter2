package org.alter.cockpit.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.alter.cockpit.auth.Principal
import org.alter.cockpit.auth.Role
import org.alter.cockpit.inbox.ApiException
import org.alter.cockpit.store.TokenStore

private val PrincipalKey = AttributeKey<Principal>("cockpit.principal")

val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]

/** Rejects the request with 403 unless the caller's role is at least [role]. */
fun ApplicationCall.require(role: Role) {
    if (!principal.role.atLeast(role)) throw ApiException(403, "This needs the ${role.name.lowercase()} role")
}

/**
 * Every route under `/api` except `/api/health` needs a token: `Authorization: Bearer <token>`, or `?token=`
 * on the event stream only, because `EventSource` can't send headers.
 */
fun Application.installTokenAuth(tokens: TokenStore) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api/") || path == "/api/health") return@intercept
        val presented = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: call.request.queryParameters["token"].takeIf { path == "/api/events" }
        val principal = presented?.let(tokens::authenticate)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing or wrong token"))
            finish()
            return@intercept
        }
        call.attributes.put(PrincipalKey, principal)
    }
}
