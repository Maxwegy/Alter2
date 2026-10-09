package org.alter.cockpit.store

import com.fasterxml.jackson.module.kotlin.readValue
import org.alter.cockpit.Json
import org.alter.cockpit.auth.Principal
import java.time.Clock
import java.time.Instant

data class AuditEntry(
    val id: Long,
    val at: String,
    val actor: String,
    val role: String,
    val action: String,
    val target: String?,
    val details: Map<String, Any?>,
)

/** Every decision and control action, who made it and with what. Append-only. */
class AuditStore(private val db: Database, private val clock: Clock = Clock.systemUTC()) {

    fun record(principal: Principal, action: String, target: String? = null, details: Map<String, Any?> = emptyMap()): AuditEntry {
        val at = Instant.now(clock).toString()
        val id = db.write { connection ->
            connection.update(
                "INSERT INTO audit_log (at, actor, role, action, target, details) VALUES (?, ?, ?, ?, ?, ?)",
                at, principal.label, principal.role.name, action, target, Json.mapper.writeValueAsString(details),
            )
            connection.query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
        }
        return AuditEntry(id, at, principal.label, principal.role.name, action, target, details)
    }

    /** Newest first. [before] pages by id. */
    fun list(limit: Int = 100, before: Long? = null): List<AuditEntry> = db.read { connection ->
        connection.query(
            "SELECT id, at, actor, role, action, target, details FROM audit_log WHERE id < ? ORDER BY id DESC LIMIT ?",
            before ?: Long.MAX_VALUE, limit,
        ) {
            AuditEntry(
                it.getLong("id"), it.getString("at"), it.getString("actor"), it.getString("role"), it.getString("action"),
                it.getString("target"), Json.mapper.readValue(it.getString("details")),
            )
        }
    }
}
