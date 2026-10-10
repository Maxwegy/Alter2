package org.alter.plugins.content.skills.resources

/**
 * The gathering rules, pure: no world, no player, no randomness of their own. The plugin copies state out of the
 * player, draws the random numbers and passes them in, so every branch can be tested with fixed values.
 */
object ResourceRules {
    /**
     * The chance out of 256 that one roll succeeds, from https://oldschool.runescape.wiki/w/Skilling_success_rate:
     * `1 + floor(low * (99 - L) / 98 + high * (L - 1) / 98 + 0.5)`, clamped to 0..256, with L clamped to 1..99.
     * Computed in integers (`+ 49` is the `+ 0.5` times 98) so no rounding error creeps in.
     */
    fun chance256(low: Int, high: Int, level: Int): Int {
        val l = level.coerceIn(1, 99)
        return (1 + (low * (99 - l) + high * (l - 1) + 49) / 98).coerceIn(0, 256)
    }

    enum class Refusal { LEVEL_TOO_LOW, NO_TOOL, TOOL_LEVEL, INVENTORY_FULL }

    sealed interface Start {
        data class Ok(val tool: ToolDef) : Start

        data class Refused(val reason: Refusal) : Start
    }

    /** The highest usable loaded tool among [held], in file order (lowest tier first). TODO tools are never picked. */
    fun bestTool(skill: SkillDef, held: Set<String>, level: Int): ToolDef? =
        skill.loadedTools.lastOrNull { it.item in held && it.level <= level }

    /**
     * Whether a player may start (or keep) gathering [node]: the node level, then a tool at all, then a tool they
     * can use, then room for the reward. [level] is the current level, so boosts count.
     */
    fun start(node: NodeDef, skill: SkillDef, level: Int, held: Set<String>, inventoryFull: Boolean, rewardStacksIntoHeld: Boolean): Start {
        if (level < node.level) return Start.Refused(Refusal.LEVEL_TOO_LOW)
        if (skill.loadedTools.none { it.item in held }) return Start.Refused(Refusal.NO_TOOL)
        val tool = bestTool(skill, held, level) ?: return Start.Refused(Refusal.TOOL_LEVEL)
        if (inventoryFull && !rewardStacksIntoHeld) return Start.Refused(Refusal.INVENTORY_FULL)
        return Start.Ok(tool)
    }

    /** Ticks until the next roll; [roll] in [0, 1) decides a fast roll for the dragon, infernal and crystal pickaxes. */
    fun rollInterval(skill: SkillDef, tool: ToolDef, roll: Double): Int {
        skill.rollIntervalTicks?.let { return it }
        val base = tool.rollIntervalTicks ?: error("${tool.item} has no roll interval")
        val fast = tool.fastRoll ?: return base
        return if (roll < fast.numerator.toDouble() / fast.denominator) fast.ticks else base
    }

    /** The success chance for [tool] at [level]: the tool's tier line, or the node's single line. */
    fun successChance(node: NodeDef, tool: ToolDef, level: Int): Int {
        val chart = when (val s = node.success) {
            is Success.ByTier -> s.charts[tool.tier] ?: error("${node.id}: no chart line for ${tool.tier}")
            is Success.ByLevel -> s.chart
            null -> error("${node.id} has no success chart")
        }
        return chance256(chart.low, chart.high, level)
    }

    /**
     * The random numbers of one roll. [preRoll] is in `0 until preRoll.outOf`, [success] in 0..255, [tertiary] and
     * [depletion] in [0, 1).
     */
    data class Rolls(val preRoll: Int, val success: Int, val tertiary: Double, val depletion: Double)

    /** What one roll gives: an item (null for nothing), XP, and whether the node depletes. */
    data class Outcome(val item: String?, val experience: Double, val depletes: Boolean, val gem: Boolean = false) {
        companion object {
            val NOTHING = Outcome(null, 0.0, false)
        }
    }

    /** The pre-roll row hit by [value], or null when [value] lies outside the table. */
    fun preRollRow(preRoll: PreRoll, value: Int): PreRollRow? {
        var cumulative = 0
        preRoll.table.forEach { row ->
            cumulative += row.weight
            if (value < cumulative) return row
        }
        return null
    }

    /**
     * One roll, in order: (1) the skill's pre-roll (mining gems: a gem gives no reward, no XP and no depletion);
     * (2) the success roll, `success < chance256`; (3) loaded tertiaries; (4) depletion on a success.
     * [timerExpired] is the node's depletion timer state for [Depletion.Timer] nodes.
     */
    fun roll(node: NodeDef, skill: SkillDef, tool: ToolDef, level: Int, rolls: Rolls, timerExpired: Boolean): Outcome {
        skill.preRoll?.let { p -> preRollRow(p, rolls.preRoll)?.item?.let { return Outcome(it, 0.0, false, gem = true) } }
        if (rolls.success >= successChance(node, tool, level)) return Outcome.NOTHING
        // A loaded tertiary always replaces the reward (the parser rejects any other kind); the first hit wins.
        val item = node.tertiary.firstOrNull { it.loaded && rolls.tertiary < it.numerator.toDouble() / it.denominator }?.item ?: node.reward
        return Outcome(item, node.experience, deplete(node.depletion ?: Depletion.Always, timerExpired, rolls.depletion))
    }

    /** Whether a successful roll depletes the node. */
    fun deplete(depletion: Depletion, timerExpired: Boolean, roll: Double): Boolean = when (depletion) {
        Depletion.Always -> true
        is Depletion.Timer -> timerExpired
        is Depletion.Chance -> roll < depletion.numerator.toDouble() / depletion.denominator
    }

    /** Ticks until a depleted node comes back; [roll] in [0, 1) picks within a range. */
    fun respawnTicks(respawn: Respawn, roll: Double): Int = when (respawn) {
        is Respawn.Fixed -> respawn.ticks
        is Respawn.Range -> respawn.min + (roll * (respawn.max - respawn.min + 1)).toInt().coerceAtMost(respawn.max - respawn.min)
    }
}
