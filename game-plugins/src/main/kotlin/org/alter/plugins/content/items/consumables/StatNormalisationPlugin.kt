package org.alter.plugins.content.items.consumables

import org.alter.api.Skills
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers

/**
 * Brings boosted and drained stats back to base and regenerates Hitpoints, so potions are temporary the way
 * they are in OSRS (one level per minute; boosted stats every 90 seconds under Preserve; combat and other skills
 * on separate clocks; 1 Hitpoint per minute, every 30 seconds with Rapid Heal). Pure pieces live in
 * [Normalisation]; this plugin only runs the clocks.
 */
class StatNormalisationPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        onLogin {
            player.timers[COMBAT_BEAT] = Normalisation.BEAT_TICKS
            player.timers[SKILL_BEAT] = Normalisation.BEAT_TICKS
            player.timers[HP_REGEN] = hpRegenTicks(player)
        }
        onTimer(COMBAT_BEAT) {
            player.timers[COMBAT_BEAT] = Normalisation.BEAT_TICKS
            val beat = (player.attr[COMBAT_BEAT_COUNT] ?: 0) + 1
            player.attr[COMBAT_BEAT_COUNT] = beat
            normalise(player, beat, COMBAT_SKILLS)
        }
        onTimer(SKILL_BEAT) {
            player.timers[SKILL_BEAT] = Normalisation.BEAT_TICKS
            val beat = (player.attr[SKILL_BEAT_COUNT] ?: 0) + 1
            player.attr[SKILL_BEAT_COUNT] = beat
            normalise(player, beat, (0 until player.getSkills().maxSkills).filter { it !in COMBAT_SKILLS && it != Skills.HITPOINTS })
        }
        onTimer(HP_REGEN) {
            player.timers[HP_REGEN] = hpRegenTicks(player)
            val skills = player.getSkills()
            val cur = skills.getCurrentLevel(Skills.HITPOINTS)
            if (cur < skills.getBaseLevel(Skills.HITPOINTS)) skills.setCurrentLevel(Skills.HITPOINTS, cur + 1)
        }
    }

    private fun normalise(player: Player, beat: Int, skillIds: List<Int>) {
        val skills = player.getSkills()
        val preserve = Prayers.isActive(player, Prayer.PRESERVE)
        for (skill in skillIds) {
            val base = skills.getBaseLevel(skill)
            val cur = skills.getCurrentLevel(skill)
            if (cur == base) continue
            if (Normalisation.shouldStep(beat, boosted = cur > base, preserve = preserve)) {
                skills.setCurrentLevel(skill, Normalisation.step(base, cur))
            }
        }
    }

    private fun hpRegenTicks(player: Player) = if (Prayers.isActive(player, Prayer.RAPID_HEAL)) 50 else 100

    companion object {
        val COMBAT_BEAT = TimerKey()
        val SKILL_BEAT = TimerKey()
        val HP_REGEN = TimerKey()
        val COMBAT_BEAT_COUNT = AttributeKey<Int>()
        val SKILL_BEAT_COUNT = AttributeKey<Int>()
        val COMBAT_SKILLS = listOf(Skills.ATTACK, Skills.DEFENCE, Skills.STRENGTH, Skills.RANGED, Skills.PRAYER, Skills.MAGIC)
    }
}
