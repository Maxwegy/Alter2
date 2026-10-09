package org.alter.cockpit.store

import com.fasterxml.jackson.module.kotlin.readValue
import org.alter.cockpit.Json
import org.alter.cockpit.inbox.ActionStatus
import org.alter.cockpit.inbox.Decision
import org.alter.cockpit.inbox.InboxAction
import org.alter.cockpit.inbox.PlanStep
import java.sql.ResultSet

class InboxStore(private val db: Database) {

    fun get(id: String): InboxAction? = db.read { it.query("$SELECT WHERE id = ?", id, map = ::row).firstOrNull() }

    fun getBySourceKey(sourceKey: String): InboxAction? =
        db.read { it.query("$SELECT WHERE source_key = ?", sourceKey, map = ::row).firstOrNull() }

    /** Open cards first (pending, running, snoozed), newest update first; [status] narrows to one state. */
    fun list(status: ActionStatus? = null, limit: Int = 200): List<InboxAction> = db.read { connection ->
        if (status == null) {
            connection.query(
                "$SELECT ORDER BY CASE status WHEN 'PENDING' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'SNOOZED' THEN 2 ELSE 3 END, updated_at DESC LIMIT ?",
                limit, map = ::row,
            )
        } else {
            connection.query("$SELECT WHERE status = ? ORDER BY updated_at DESC LIMIT ?", status.name, limit, map = ::row)
        }
    }

    fun insert(action: InboxAction) {
        db.write { connection ->
            connection.update(
                """
                INSERT INTO inbox_actions (id, kind, source_key, title, summary, evidence, plan, params, status, decision,
                    decision_reason, result, error, created_at, updated_at, snoozed_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                action.id, action.kind, action.sourceKey, action.title, action.summary, json(action.evidence), json(action.plan),
                json(action.params), action.status.name, action.decision?.name, action.decisionReason, action.result?.let(::json),
                action.error, action.createdAt, action.updatedAt, action.snoozedUntil,
            )
        }
    }

    fun update(action: InboxAction) {
        db.write { connection ->
            connection.update(
                """
                UPDATE inbox_actions SET title = ?, summary = ?, evidence = ?, plan = ?, params = ?, status = ?, decision = ?,
                    decision_reason = ?, result = ?, error = ?, updated_at = ?, snoozed_until = ?
                WHERE id = ?
                """.trimIndent(),
                action.title, action.summary, json(action.evidence), json(action.plan), json(action.params), action.status.name,
                action.decision?.name, action.decisionReason, action.result?.let(::json), action.error, action.updatedAt,
                action.snoozedUntil, action.id,
            )
        }
    }

    fun counts(): Map<ActionStatus, Int> = db.read { connection ->
        connection.query("SELECT status, COUNT(*) FROM inbox_actions GROUP BY status") { ActionStatus.valueOf(it.getString(1)) to it.getInt(2) }.toMap()
    }

    private fun json(value: Any): String = Json.mapper.writeValueAsString(value)

    private fun row(rs: ResultSet) = InboxAction(
        id = rs.getString("id"),
        kind = rs.getString("kind"),
        sourceKey = rs.getString("source_key"),
        title = rs.getString("title"),
        summary = rs.getString("summary"),
        evidence = Json.mapper.readValue(rs.getString("evidence")),
        plan = Json.mapper.readValue<List<PlanStep>>(rs.getString("plan")),
        params = Json.mapper.readValue(rs.getString("params")),
        status = ActionStatus.valueOf(rs.getString("status")),
        decision = rs.getString("decision")?.let(Decision::valueOf),
        decisionReason = rs.getString("decision_reason"),
        result = rs.getString("result")?.let { Json.mapper.readValue<Map<String, Any?>>(it) },
        error = rs.getString("error"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
        snoozedUntil = rs.getString("snoozed_until"),
    )

    private companion object {
        const val SELECT = "SELECT id, kind, source_key, title, summary, evidence, plan, params, status, decision, decision_reason, result, error, created_at, updated_at, snoozed_until FROM inbox_actions"
    }
}
