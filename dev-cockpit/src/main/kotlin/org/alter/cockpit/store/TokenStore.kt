package org.alter.cockpit.store

import org.alter.cockpit.auth.Principal
import org.alter.cockpit.auth.Role
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

data class ApiToken(val id: Long, val role: Role, val label: String, val createdAt: String, val lastUsedAt: String?)

/** API tokens. Only a SHA-256 hash is stored; the plaintext is shown once when a token is issued. */
class TokenStore(private val db: Database, private val clock: Clock = Clock.systemUTC()) {

    /** Creates a token and returns its plaintext together with the stored record. */
    fun issue(role: Role, label: String): Pair<String, ApiToken> {
        val token = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = Instant.now(clock).toString()
        val id = db.write { connection ->
            connection.update("INSERT INTO api_tokens (token_hash, role, label, created_at) VALUES (?, ?, ?, ?)", hash(token), role.name, label, now)
            connection.query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
        }
        return token to ApiToken(id, role, label, now, null)
    }

    fun authenticate(token: String): Principal? {
        val hashed = hash(token)
        val principal = db.read { connection ->
            connection.query("SELECT id, role, label FROM api_tokens WHERE token_hash = ?", hashed) {
                Principal(it.getLong("id"), Role.valueOf(it.getString("role")), it.getString("label"))
            }.firstOrNull()
        } ?: return null
        db.write { it.update("UPDATE api_tokens SET last_used_at = ? WHERE id = ?", Instant.now(clock).toString(), principal.tokenId) }
        return principal
    }

    fun list(): List<ApiToken> = db.read { connection ->
        connection.query("SELECT id, role, label, created_at, last_used_at FROM api_tokens ORDER BY id") {
            ApiToken(it.getLong("id"), Role.valueOf(it.getString("role")), it.getString("label"), it.getString("created_at"), it.getString("last_used_at"))
        }
    }

    fun revoke(id: Long): Boolean = db.write { it.update("DELETE FROM api_tokens WHERE id = ?", id) } == 1

    fun count(): Int = db.read { it.query("SELECT COUNT(*) FROM api_tokens") { row -> row.getInt(1) }.first() }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
