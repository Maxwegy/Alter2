package org.alter.game.plugin

import org.alter.game.model.Tile

/** The kinds of player interaction that can reach a "nothing handles this" fallback. */
enum class InteractionType {
    NPC_OP,
    ITEM_ON_NPC,
    SPELL_ON_NPC,
    PLAYER_OP,
    SPELL_ON_PLAYER,
    LOC_OP,
    ITEM_ON_LOC,
    GROUND_OP,
    ITEM_ON_GROUND,
    SPELL_ON_GROUND,
    INV_OP,
    ITEM_ON_ITEM,
    SPELL_ON_ITEM,
    WORN_OP,
    IF_BUTTON,
    IF_DRAG,
    COMBAT_SPELL,
}

/**
 * An interaction that no plugin handled, reported to the single hook bound with
 * [PluginRepository.bindUnhandledInteraction].
 *
 * @param id the id plugins are keyed on (for NPCs and objects: the transformed id the player sees).
 * @param rawId the untransformed entity id, when it differs from [id].
 * @param op the option index or raw button op, -1 when not applicable.
 * @param usedId the item or spell used on the target, -1 when none.
 * @param component interface component (packed `interface << 16 | component` for buttons), -1 when none.
 */
data class UnhandledInteraction(
    val type: InteractionType,
    val id: Int,
    val rawId: Int = id,
    val op: Int = -1,
    val usedId: Int = -1,
    val component: Int = -1,
    val targetComponent: Int = -1,
    val slot: Int = -1,
    val tile: Tile? = null,
)
