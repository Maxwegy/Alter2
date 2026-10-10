package org.alter.plugins.content.combat.specialattack

import org.alter.api.EquipmentType
import org.alter.api.NpcSkills
import org.alter.api.NpcSpecies
import org.alter.api.Skills
import org.alter.api.ext.freeze
import org.alter.api.ext.getEquipment
import org.alter.api.ext.hit
import org.alter.api.ext.isSpecies
import org.alter.api.ext.message
import org.alter.api.ext.playSound
import org.alter.api.ext.sendRunEnergy
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.items.consumables.Consumables

/**
 * Fires special attacks from `data/cfg/combat/special_attacks.json`. The numbers and the decisions live in the
 * file and [SpecialRules]; this object copies the state out of the world, applies the plan and talks to the client.
 * Everything runs on the game thread.
 */
object SpecialAttacks {
    /** Set by [SpecialAttacksPlugin] when it loads the service. */
    @Volatile
    internal var service: SpecialAttacksService? = null

    /** NPCs have only these skills (no Prayer). */
    private val NPC_SKILLS: Map<String, Int> = mapOf(
        "attack" to NpcSkills.ATTACK, "strength" to NpcSkills.STRENGTH, "defence" to NpcSkills.DEFENCE,
        "magic" to NpcSkills.MAGIC, "ranged" to NpcSkills.RANGED,
    )

    /** The loaded special of the player's weapon; null when it has none or its entry is a TODO. */
    fun lookup(player: Player): SpecialDef? {
        val weapon = player.getEquipment(EquipmentType.WEAPON) ?: return null
        return service?.lookup(weapon.id)
    }

    /** True when the weapon's special leaves no attack cooldown (the granite maul). */
    fun skipsAttackDelay(player: Player): Boolean = lookup(player)?.noAttackDelay == true

    /**
     * Fires the weapon's special at [target] (null for self boosts). Returns false, with nothing spent, when the
     * weapon has no loaded special, a target is needed but missing, or the energy is too low.
     */
    fun execute(
        player: Player,
        target: Pawn?,
        world: World,
    ): Boolean {
        val def = lookup(player) ?: return false
        if (def.hits.isNotEmpty() && target == null) return false
        val skills = player.getSkills()
        val self = playerLevels(player)
        val energy = AttackTab.getEnergy(player)
        val state = if (target != null && def.hits.isNotEmpty()) {
            SpecialState(
                energy = energy,
                vsNpc = target is Npc,
                targetSize = target.getSize(),
                baseMaxHit = MeleeCombatFormula.getMaxHit(player, target),
                missingPrayer = (skills.getBaseLevel(Skills.PRAYER) - skills.getCurrentLevel(Skills.PRAYER)).coerceAtLeast(0),
                accuracy = SpecialRules.accuracyMultipliers(def).associateWith { MeleeCombatFormula.getAccuracy(player, target, it) },
                rolls = def.hits.map { world.randomDouble() },
                self = self,
            )
        } else {
            SpecialState(energy = energy, self = self)
        }
        val plan = when (val decision = SpecialRules.decide(def, state)) {
            is SpecialDecision.Refused -> {
                service?.messages?.insufficientEnergy?.let { player.message(it) }
                return false
            }
            is SpecialDecision.Fire -> decision.plan
        }

        AttackTab.setEnergy(player, plan.energyAfter)
        def.animation?.let { player.animate(it) }
        def.graphic?.let { player.graphic(it.id, it.height) }
        def.sound?.let { player.playSound(it.id, delay = it.delay) }
        def.forceChat?.let { player.forceChat(it) }
        plan.selfLevels.forEach { (skill, level) -> skills.setCurrentLevel(Consumables.SKILLS.getValue(skill), level) }

        if (target != null && plan.hits.isNotEmpty()) {
            val damages = IntArray(plan.hits.size)
            plan.hits.forEachIndexed { i, hit ->
                val last = i == plan.hits.lastIndex
                val pawnHit = player.dealHit(target = target, maxHit = hit.maxHit, landHit = hit.landed, delay = hit.delay) {
                    if (last) applyOutcome(player, target, def, plan, damages.toList(), world)
                }
                damages[i] = pawnHit.hit.hitmarks.sumOf { it.damage }
            }
        }
        return true
    }

    /** Applies the damage-dependent effects as the last hit lands, reading the target as it is then. */
    private fun applyOutcome(player: Player, target: Pawn, def: SpecialDef, plan: SpecialPlan, damages: List<Int>, world: World) {
        val targetState = when (target) {
            is Player -> TargetState(playerLevels(target), isPlayer = true, runEnergy = target.runEnergy.toInt())
            is Npc -> TargetState(
                NPC_SKILLS.mapValues { (_, id) -> Level(target.stats.getCurrentLevel(id), target.stats.getMaxLevel(id)) },
                isPlayer = false,
                isDemon = target.isSpecies(NpcSpecies.DEMON),
            )
            else -> return
        }
        val outcome = SpecialRules.afterRoll(def, plan, damages, AfterRollState(playerLevels(player), player.runEnergy.toInt(), targetState, world.randomDouble()))
        outcome.selfLevels.forEach { (skill, level) -> player.getSkills().setCurrentLevel(Consumables.SKILLS.getValue(skill), level) }
        outcome.targetLevels.forEach { (skill, level) ->
            when (target) {
                is Player -> target.getSkills().setCurrentLevel(Consumables.SKILLS.getValue(skill), level)
                is Npc -> NPC_SKILLS[skill]?.let { target.stats.setCurrentLevel(it, level) }
            }
        }
        outcome.freezeTicks?.let { target.freeze(it) }
        outcome.runEnergy?.let { transfer ->
            if (target !is Player) return@let
            player.runEnergy = transfer.selfAfter.toDouble()
            player.sendRunEnergy(transfer.selfAfter / 100)
            target.runEnergy = transfer.targetAfter.toDouble()
            target.sendRunEnergy(transfer.targetAfter / 100)
            transfer.targetMessage?.let { target.message(it) }
        }
        outcome.extraMagicHit?.let { damage ->
            target.hit(damage = damage, attackersIndex = player.index)
            target.damageMap.add(player, damage)
            if (outcome.magicXp > 0) player.addXp(Skills.MAGIC, outcome.magicXp)
        }
    }

    private fun playerLevels(player: Player): Map<String, Level> {
        val skills = player.getSkills()
        return Consumables.SKILLS.mapValues { (_, id) -> Level(skills.getCurrentLevel(id), skills.getBaseLevel(id)) }
    }
}
