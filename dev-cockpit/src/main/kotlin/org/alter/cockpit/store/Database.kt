package org.alter.cockpit.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The cockpit's SQLite database: one connection, every access serialized, WAL on disk. SQLite is plenty for
 * a single local process, and one file keeps backups and resets trivial (`data/cockpit/cockpit.db`).
 */
class Database private constructor(private val connection: Connection) : AutoCloseable {
    private val lock = Any()

    fun <T> read(block: (Connection) -> T): T = synchronized(lock) { block(connection) }

    /** Runs [block] in a transaction; any exception rolls it back. */
    fun <T> write(block: (Connection) -> T): T = synchronized(lock) {
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun exec(sql: String) {
        read { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    override fun close() = connection.close()

    companion object {
        fun open(file: Path): Database {
            Files.createDirectories(file.toAbsolutePath().parent)
            return connect("jdbc:sqlite:${file.toAbsolutePath()}").also { it.exec("PRAGMA journal_mode=WAL") }
        }

        fun inMemory(): Database = connect("jdbc:sqlite::memory:")

        private fun connect(url: String): Database {
            val db = Database(DriverManager.getConnection(url))
            db.exec("PRAGMA foreign_keys=ON")
            Migrations.apply(db)
            return db
        }
    }
}

internal fun PreparedStatement.bind(args: Array<out Any?>): PreparedStatement {
    args.forEachIndexed { index, value -> setObject(index + 1, value) }
    return this
}

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(args).executeQuery().use { rows ->
            buildList { while (rows.next()) add(map(rows)) }
        }
    }

internal fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { it.bind(args).executeUpdate() }
