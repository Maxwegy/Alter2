package dev.openrune.cache.filestore.definition.data

import kotlinx.serialization.Serializable

/**
 * An entity option that is only shown while a varp/varbit value is within [min]..[max] (config opcodes
 * NPC 252, item 201/202, loc 101/102, added between revisions 229 and 241). [subId] is -1 for a main option.
 */
@Serializable
data class ConditionalOp(
    val index: Int,
    val subId: Int = -1,
    val varp: Int,
    val varbit: Int,
    val min: Int,
    val max: Int,
    val text: String,
)
