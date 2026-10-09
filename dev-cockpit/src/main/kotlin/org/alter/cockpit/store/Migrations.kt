package org.alter.cockpit.store

/**
 * Schema versions, applied in order and recorded in `schema_version`. Never edit a shipped step; add a
 * new one. JSON columns hold structured data the API passes through as-is.
 */
object Migrations {
    private val steps: List<Pair<Int, List<String>>> = listOf(
        1 to listOf(
            """
            CREATE TABLE api_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                token_hash TEXT NOT NULL UNIQUE,
                role TEXT NOT NULL,
                label TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_used_at TEXT
            )
            """,
            """
            CREATE TABLE inbox_actions (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                source_key TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                evidence TEXT NOT NULL,
                plan TEXT NOT NULL,
                params TEXT NOT NULL,
                status TEXT NOT NULL,
                decision TEXT,
                decision_reason TEXT,
                result TEXT,
                error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                snoozed_until TEXT
            )
            """,
            "CREATE INDEX inbox_actions_status ON inbox_actions (status, updated_at)",
            """
            CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at TEXT NOT NULL,
                actor TEXT NOT NULL,
                role TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT,
                details TEXT NOT NULL
            )
            """,
        ),
    )

    val version: Int get() = steps.last().first

    fun apply(db: Database) {
        db.exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        val current = db.read { connection ->
            connection.query("SELECT MAX(version) FROM schema_version") { it.getInt(1) }.firstOrNull() ?: 0
        }
        steps.filter { (version, _) -> version > current }.forEach { (version, statements) ->
            db.write { connection ->
                statements.forEach { sql -> connection.createStatement().use { it.execute(sql.trimIndent()) } }
                connection.update("INSERT INTO schema_version (version) VALUES (?)", version)
            }
        }
    }
}
