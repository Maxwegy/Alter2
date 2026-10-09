package org.alter.cockpit.auth

/** Roles, strongest first. [atLeast] is the only comparison the routes need. */
enum class Role {
    OWNER, DEV, VIEWER;

    fun atLeast(required: Role): Boolean = ordinal <= required.ordinal
}

/** Who is making a request, as resolved from their API token. */
data class Principal(val tokenId: Long, val role: Role, val label: String)
