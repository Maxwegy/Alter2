package org.alter.cockpit.inbox

/**
 * Does what a card promises when the user presses GO. Executors are plain classes wired in [org.alter.cockpit.Cockpit];
 * the result map is stored on the card and shown in the UI. Throwing marks the card FAILED with the message.
 */
interface ActionExecutor {
    /** Whether this executor handles cards of [kind] (exact kind or a dotted prefix such as `enrich.`). */
    fun handles(kind: String): Boolean

    suspend fun execute(action: InboxAction): Map<String, Any?>
}

/** A request that the API turns into a 4xx response instead of a 500. */
class ApiException(val status: Int, message: String) : RuntimeException(message)
