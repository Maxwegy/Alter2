package org.alter.cockpit.inbox

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.alter.cockpit.auth.Principal
import org.alter.cockpit.events.EventBus
import org.alter.cockpit.store.AuditStore
import org.alter.cockpit.store.InboxStore
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The inbox: sources propose cards, people decide (GO, Edit, Delete, Snooze), executors do the work.
 * Every decision is audited and published on the bus; the store is the only state.
 */
class InboxService(
    private val store: InboxStore,
    private val audit: AuditStore,
    private val bus: EventBus,
    private val executors: List<ActionExecutor>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = KotlinLogging.logger {}

    fun get(id: String): InboxAction = store.get(id) ?: throw ApiException(404, "No inbox card $id")

    fun list(status: ActionStatus? = null): List<InboxAction> = store.list(status)

    fun counts(): Map<ActionStatus, Int> = store.counts()

    /**
     * Called by sources. Creates the card if [sourceKey] is new, refreshes evidence and summary while the
     * card is still open, and leaves decided cards (done, deleted) alone so a decision sticks.
     * Returns the card when it was created, null otherwise.
     */
    fun propose(
        kind: String,
        sourceKey: String,
        title: String,
        summary: String,
        evidence: Map<String, Any?> = emptyMap(),
        plan: List<PlanStep> = emptyList(),
        params: Map<String, Any?> = emptyMap(),
    ): InboxAction? {
        val existing = store.getBySourceKey(sourceKey)
        if (existing == null) {
            val now = now()
            val action = InboxAction(UUID.randomUUID().toString(), kind, sourceKey, title, summary, evidence, plan, params, createdAt = now, updatedAt = now)
            store.insert(action)
            bus.publish("inbox.created", action)
            return action
        }
        if (existing.status in OPEN && (existing.evidence != evidence || existing.summary != summary)) {
            val updated = existing.copy(evidence = evidence, summary = summary, updatedAt = now())
            store.update(updated)
            bus.publish("inbox.updated", updated)
        }
        return null
    }

    /** GO: optional last-minute [params] edits, then the executor runs in the background. */
    fun go(id: String, principal: Principal, params: Map<String, Any?>? = null): InboxAction {
        val action = get(id)
        if (action.status !in RUNNABLE) throw ApiException(409, "Card is ${action.status}; only pending, snoozed or failed cards can run")
        val edited = params != null && params != action.params
        val running = action.copy(
            params = params ?: action.params,
            status = ActionStatus.RUNNING,
            decision = if (edited || action.decision == Decision.EDIT) Decision.EDIT else Decision.GO,
            error = null,
            result = null,
            snoozedUntil = null,
            updatedAt = now(),
        )
        store.update(running)
        audit.record(principal, "inbox.go", id, mapOf("kind" to action.kind, "edited" to edited, "params" to running.params))
        bus.publish("inbox.updated", running)
        scope.launch { execute(running) }
        return running
    }

    private suspend fun execute(action: InboxAction) {
        val executor = executors.firstOrNull { it.handles(action.kind) }
        val finished = try {
            if (executor == null) throw IllegalStateException("No executor for kind ${action.kind}")
            action.copy(status = ActionStatus.DONE, result = executor.execute(action), updatedAt = now())
        } catch (e: Exception) {
            logger.warn(e) { "Inbox card ${action.id} (${action.kind}) failed" }
            action.copy(status = ActionStatus.FAILED, error = e.message ?: e::class.simpleName, updatedAt = now())
        }
        store.update(finished)
        bus.publish("inbox.updated", finished)
    }

    fun edit(id: String, principal: Principal, params: Map<String, Any?>): InboxAction {
        val action = get(id)
        if (action.status !in RUNNABLE) throw ApiException(409, "Card is ${action.status}; it can't be edited")
        val edited = action.copy(params = params, decision = Decision.EDIT, updatedAt = now())
        store.update(edited)
        audit.record(principal, "inbox.edit", id, mapOf("kind" to action.kind, "params" to params))
        bus.publish("inbox.updated", edited)
        return edited
    }

    /** Delete needs a [reason]: it is what the automation rules learn from later. */
    fun delete(id: String, principal: Principal, reason: String): InboxAction {
        if (reason.isBlank()) throw ApiException(400, "A reason is required to delete a card")
        val action = get(id)
        if (action.status == ActionStatus.DELETED) throw ApiException(409, "Card is already deleted")
        val deleted = action.copy(status = ActionStatus.DELETED, decision = Decision.DELETE, decisionReason = reason, snoozedUntil = null, updatedAt = now())
        store.update(deleted)
        audit.record(principal, "inbox.delete", id, mapOf("kind" to action.kind, "reason" to reason))
        bus.publish("inbox.updated", deleted)
        return deleted
    }

    fun snooze(id: String, principal: Principal, until: Instant): InboxAction {
        val action = get(id)
        if (action.status !in RUNNABLE) throw ApiException(409, "Card is ${action.status}; it can't be snoozed")
        val snoozed = action.copy(status = ActionStatus.SNOOZED, decision = Decision.SNOOZE, snoozedUntil = until.toString(), updatedAt = now())
        store.update(snoozed)
        audit.record(principal, "inbox.snooze", id, mapOf("kind" to action.kind, "until" to until.toString()))
        bus.publish("inbox.updated", snoozed)
        return snoozed
    }

    /** Puts snoozed cards whose time is up back in the inbox. Returns how many woke. */
    fun wakeSnoozed(): Int {
        val now = Instant.now(clock)
        val due = store.list(ActionStatus.SNOOZED).filter { it.snoozedUntil != null && Instant.parse(it.snoozedUntil) <= now }
        due.forEach { action ->
            val woken = action.copy(status = ActionStatus.PENDING, snoozedUntil = null, updatedAt = now.toString())
            store.update(woken)
            bus.publish("inbox.updated", woken)
        }
        return due.size
    }

    private fun now(): String = Instant.now(clock).toString()

    private companion object {
        val OPEN = setOf(ActionStatus.PENDING, ActionStatus.SNOOZED, ActionStatus.RUNNING)
        val RUNNABLE = setOf(ActionStatus.PENDING, ActionStatus.SNOOZED, ActionStatus.FAILED)
    }
}
