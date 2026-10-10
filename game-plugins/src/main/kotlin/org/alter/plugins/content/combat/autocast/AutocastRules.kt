package org.alter.plugins.content.combat.autocast

/**
 * The autocast rules from https://oldschool.runescape.wiki/w/Autocast, free of the world so they can be unit
 * tested. A selection is an autocast id (0 = none). The memory holds the last selection per [SpellGroup], so
 * weapons that share a group remember the previous choice.
 */
object AutocastRules {
    const val NONE = 0

    /** Whether [spell] may be autocast with a weapon whose groups are [allowed]. */
    fun allowed(allowed: Collection<SpellGroup>, spell: Int): Boolean = spell != NONE && allowed.any { it.contains(spell) }

    /**
     * The selection after the weapon changed (or on login):
     * - equipping a staff while the PvP swap lock runs clears it ("equip a staff within 12 seconds after attacking a player in a PVP area");
     * - a selection the new weapon may cast is kept;
     * - otherwise the remembered spell of the new weapon's groups is restored, [preferredGroup] first;
     * - otherwise it is cleared.
     */
    fun onWeaponChange(
        current: Int,
        allowed: List<SpellGroup>,
        memory: Map<String, Int>,
        equipping: Boolean,
        pvpLocked: Boolean,
        preferredGroup: String? = null,
    ): Int {
        if (equipping && pvpLocked && allowed.isNotEmpty()) return NONE
        if (allowed(allowed, current)) return current
        val ordered = allowed.sortedBy { if (it.name == preferredGroup) 0 else 1 }
        for (group in ordered) {
            val remembered = memory[group.name] ?: continue
            if (group.contains(remembered)) return remembered
        }
        return NONE
    }

    /** The memory after [spell] was chosen, or null when the weapon may not cast it. */
    fun onSelect(spell: Int, allowed: List<SpellGroup>, memory: Map<String, Int>): Map<String, Int>? {
        val group = allowed.firstOrNull { it.contains(spell) } ?: return null
        return memory + (group.name to spell)
    }

    /** "group=SPELL;group=SPELL", spells by [org.alter.plugins.content.combat.strategy.magic.CombatSpell] name, groups in [groups] order. */
    fun encode(memory: Map<String, Int>, groups: List<SpellGroup>): String =
        groups.mapNotNull { g -> memory[g.name]?.let { id -> g.nameOf(id)?.let { "${g.name}=$it" } } }.joinToString(";")

    /** The inverse of [encode]; unknown groups or spells (e.g. after a data change) are dropped. */
    fun decode(text: String?, groups: List<SpellGroup>): Map<String, Int> {
        if (text.isNullOrBlank()) return emptyMap()
        val byName = groups.associateBy { it.name }
        return text.split(';').mapNotNull { part ->
            val group = byName[part.substringBefore('=', "")] ?: return@mapNotNull null
            val id = group.spells[part.substringAfter('=', "")] ?: return@mapNotNull null
            group.name to id
        }.toMap()
    }
}
