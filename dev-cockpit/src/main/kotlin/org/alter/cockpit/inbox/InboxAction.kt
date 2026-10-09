package org.alter.cockpit.inbox

enum class ActionStatus { PENDING, SNOOZED, RUNNING, DONE, FAILED, DELETED }

enum class Decision { GO, EDIT, DELETE, SNOOZE }

/** One thing the action will do, shown before GO: a request it makes, a file it writes, a command it runs. */
data class PlanStep(val type: String, val description: String, val target: String? = null) {
    companion object {
        const val REQUEST = "request"
        const val WRITE = "write"
        const val RUN = "run"
    }
}

/**
 * A card in the inbox: something the system proposes to do, with the evidence behind it and the exact plan.
 * [sourceKey] identifies what the card is about (one card per missing-content key, per server exit...), so a
 * source can refresh evidence without creating duplicates.
 */
data class InboxAction(
    val id: String,
    /** Dotted action kind, e.g. `enrich.npc_op`, `server.start`. Executors and (later) auto rules match on it. */
    val kind: String,
    val sourceKey: String,
    val title: String,
    val summary: String,
    val evidence: Map<String, Any?> = emptyMap(),
    val plan: List<PlanStep> = emptyList(),
    /** Editable parameters; Edit changes these, GO hands them to the executor. */
    val params: Map<String, Any?> = emptyMap(),
    val status: ActionStatus = ActionStatus.PENDING,
    val decision: Decision? = null,
    val decisionReason: String? = null,
    val result: Map<String, Any?>? = null,
    val error: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val snoozedUntil: String? = null,
)
