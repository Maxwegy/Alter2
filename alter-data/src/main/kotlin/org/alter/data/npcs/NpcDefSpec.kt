package org.alter.data.npcs

import org.alter.data.snapshot.NpcEntry

/**
 * What the server's own cache says about an NPC's combat stats, extracted by the game-side adapter.
 *
 * @param levels attack, defence, strength, hitpoints, ranged, magic (cache opcode order), or null when the
 *   NPC has no stats in the cache (every level is the decoder default of 1).
 * @param params the NPC's cache params (bonuses 0-9, melee strength 10, ranged strength 12, attack rate 14,
 *   magic damage 65).
 */
data class NpcCacheStats(val levels: List<Int>?, val params: Map<Int, Int>) {
    companion object {
        fun of(attack: Int, defence: Int, strength: Int, hitpoints: Int, ranged: Int, magic: Int, params: Map<Int, Int>): NpcCacheStats {
            val levels = listOf(attack, defence, strength, hitpoints, ranged, magic)
            return NpcCacheStats(levels.takeIf { all -> all.any { it != 1 } }, params)
        }
    }
}

/**
 * The combat definition the server should use for an NPC, merged with precedence cache > snapshot > default.
 * Hand-written `setCombatDef` definitions and override files win over this entirely; they are applied by
 * the caller. [sources] records where each value came from, for `::wikinpc`.
 */
data class NpcDefSpec(
    val hitpoints: Int,
    val attack: Int,
    val strength: Int,
    val defence: Int,
    val magic: Int,
    val ranged: Int,
    val attackSpeed: Int,
    val respawnTicks: Int?,
    /** 14 bonuses in the server's NPC layout: 0-9 attack/defence stab, slash, crush, magic, ranged; 10 attack; 11 strength; 12 ranged strength; 13 magic damage. */
    val bonuses: List<Int>,
    val aggressive: Boolean,
    val immunePoison: Boolean,
    val immuneVenom: Boolean,
    val immuneCannon: Boolean,
    val immuneThrall: Boolean,
    val slayerLevel: Int,
    val slayerXp: Double,
    val attributes: List<String>,
    val sources: Map<String, String>,
) {
    companion object {
        const val DEFAULT_ATTACK_SPEED = 4
        const val CACHE = "cache"
        const val WIKI = "wiki"
        const val DEFAULT = "default"

        /** Cache param → bonus slot. Params 0-9 share the slot index. */
        private val PARAM_TO_SLOT: Map<Int, Int> = (0..9).associateWith { it } + mapOf(10 to 11, 12 to 12, 65 to 13)
        private const val ATTACK_RATE_PARAM = 14

        fun merge(entry: NpcEntry, cache: NpcCacheStats?): NpcDefSpec {
            val sources = linkedMapOf<String, String>()
            fun <T> pick(field: String, fromCache: T?, fromWiki: T?, default: T): T = when {
                fromCache != null -> fromCache.also { sources[field] = CACHE }
                fromWiki != null -> fromWiki.also { sources[field] = WIKI }
                else -> default.also { sources[field] = DEFAULT }
            }

            val cacheLevels = cache?.levels
            val wikiLevels = entry.levels
            val wikiBonuses = entry.bonuses?.let {
                listOf(
                    it.stabAttack, it.slashAttack, it.crushAttack, it.magicAttack, it.rangedAttack,
                    it.stabDefence, it.slashDefence, it.crushDefence, it.magicDefence, it.rangedDefence,
                    it.attackBonus, it.strengthBonus, it.rangedStrength, it.magicDamage,
                )
            }
            val cacheBySlot = cache?.params.orEmpty().mapNotNull { (param, value) -> PARAM_TO_SLOT[param]?.let { it to value } }.toMap()
            val bonuses = List(14) { slot -> cacheBySlot[slot] ?: wikiBonuses?.get(slot) ?: 0 }
            sources["bonuses"] = when {
                cacheBySlot.isNotEmpty() && wikiBonuses != null -> "$CACHE+$WIKI"
                cacheBySlot.isNotEmpty() -> CACHE
                wikiBonuses != null -> WIKI
                else -> DEFAULT
            }

            return NpcDefSpec(
                hitpoints = pick("hitpoints", cacheLevels?.get(3), entry.hitpoints, 10),
                attack = pick("attack", cacheLevels?.get(0), wikiLevels?.attack, 1),
                strength = pick("strength", cacheLevels?.get(2), wikiLevels?.strength, 1),
                defence = pick("defence", cacheLevels?.get(1), wikiLevels?.defence, 1),
                magic = pick("magic", cacheLevels?.get(5), wikiLevels?.magic, 1),
                ranged = pick("ranged", cacheLevels?.get(4), wikiLevels?.ranged, 1),
                attackSpeed = pick("attackSpeed", cache?.params?.get(ATTACK_RATE_PARAM), entry.attackSpeed, DEFAULT_ATTACK_SPEED),
                respawnTicks = entry.respawnTicks.also { sources["respawnTicks"] = if (it != null) WIKI else DEFAULT },
                bonuses = bonuses,
                aggressive = entry.aggressive == true,
                immunePoison = entry.immunities.poison,
                immuneVenom = entry.immunities.venom,
                immuneCannon = entry.immunities.cannon,
                immuneThrall = entry.immunities.thrall,
                slayerLevel = entry.slayer?.level ?: 1,
                slayerXp = entry.slayer?.xp ?: 0.0,
                attributes = entry.attributes,
                sources = sources,
            )
        }
    }
}
