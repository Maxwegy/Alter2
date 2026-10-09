package org.alter.plugins.content.infrastructure.missingcontent

import dev.openrune.cache.CacheManager
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.isPrivilegeEligible
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.data.missing.Location
import org.alter.data.missing.MissingContentEvent
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.InteractionType
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.plugin.UnhandledInteraction

/**
 * Turns every interaction no plugin handles into a missing-content entry, and tells developers about it
 * in chat. Regular players only ever see the normal fallback message.
 */
class MissingContentPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    private val service by lazy { world.getService(MissingContentService::class.java) }

    /** Last dev-message time per player and key, so a repeated click doesn't spam chat. Game thread only. */
    private val lastNotified = HashMap<String, Long>()

    init {
        onUnhandledInteraction { player, interaction ->
            val service = service ?: return@onUnhandledInteraction
            val event = toEvent(interaction)
            service.record(event)
            if (player.isPrivilegeEligible(Privilege.DEV_POWER)) {
                notifyDeveloper(player, event, service.config.devMessageCooldownSeconds)
            }
        }

        onCommand("missing", Privilege.DEV_POWER, description = "List the most common unhandled interactions") {
            val service = service ?: return@onCommand player.message("The missing-content log is disabled.")
            val count = player.getCommandArgs().firstOrNull()?.toIntOrNull() ?: 10
            val top = service.top(count)
            if (top.isEmpty()) {
                player.message("No missing content recorded yet.")
            }
            top.forEach { entry ->
                player.message("${entry.count}x ${entry.type} ${entry.name ?: "?"} (${entry.id}) ${entry.optionName ?: opLabel(entry.op)}".trimEnd())
            }
        }
    }

    private fun notifyDeveloper(player: Player, event: MissingContentEvent, cooldownSeconds: Long) {
        val now = System.currentTimeMillis()
        val key = "${player.username}|${event.key}"
        val last = lastNotified[key]
        if (last != null && now - last < cooldownSeconds * 1000) {
            return
        }
        lastNotified[key] = now
        val option = event.optionName?.let { " '$it'" } ?: ""
        val used = if (event.usedId != -1) " using ${event.usedId}" else ""
        val at = event.location?.let { " @ ${it.x},${it.z},${it.height}" } ?: ""
        player.message("[missing] ${event.type} ${event.name ?: "?"} (${event.id})${opLabel(event.op).prefixed()}$option$used$at")
    }

    private fun opLabel(op: Int) = if (op >= 0) "op$op" else ""

    private fun String.prefixed() = if (isEmpty()) this else " $this"

    private fun toEvent(interaction: UnhandledInteraction): MissingContentEvent {
        val (name, optionName) = describe(interaction)
        return MissingContentEvent(
            type = interaction.type.name,
            id = interaction.id,
            rawId = interaction.rawId,
            op = interaction.op,
            usedId = interaction.usedId,
            component = interaction.component,
            name = name,
            optionName = optionName,
            location = interaction.tile?.let { Location(it.x, it.z, it.height) },
        )
    }

    /** Entity name and, where the option index is reliable, the option text. */
    private fun describe(interaction: UnhandledInteraction): Pair<String?, String?> {
        val id = interaction.id
        val op = interaction.op
        return when (interaction.type) {
            InteractionType.NPC_OP -> npc(id)?.let { it.name to it.actions.getOrNull(op - 1) } ?: (null to null)
            InteractionType.ITEM_ON_NPC, InteractionType.SPELL_ON_NPC -> npc(id)?.name to null
            InteractionType.LOC_OP -> obj(id)?.let { it.name to it.actions.getOrNull(op - 1) } ?: (null to null)
            InteractionType.ITEM_ON_LOC -> obj(id)?.name to null
            InteractionType.GROUND_OP -> item(id)?.let { it.name to it.options.getOrNull(op - 1) } ?: (null to null)
            // Inventory option numbering is not verified yet (raw ops vs. bound indices), so no option text.
            InteractionType.ITEM_ON_GROUND, InteractionType.SPELL_ON_GROUND, InteractionType.INV_OP,
            InteractionType.ITEM_ON_ITEM, InteractionType.SPELL_ON_ITEM, InteractionType.WORN_OP,
            -> item(id)?.name to null
            InteractionType.PLAYER_OP, InteractionType.SPELL_ON_PLAYER, InteractionType.IF_BUTTON,
            InteractionType.IF_DRAG, InteractionType.COMBAT_SPELL,
            -> null to null
        }
    }

    private fun npc(id: Int) = runCatching { CacheManager.getNpc(id) }.getOrNull()

    private fun obj(id: Int) = runCatching { CacheManager.getObject(id) }.getOrNull()

    private fun item(id: Int) = runCatching { CacheManager.getItem(id) }.getOrNull()
}
