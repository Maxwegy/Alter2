package org.alter.data.spawns

/** Test entries with the ids the tools would give them. */
internal object Entries {
    fun manual(npc: String, x: Int, z: Int, height: Int, walkRadius: Int, direction: String? = null, note: String? = null) =
        NpcSpawnEntry(SpawnIds.minted(npc, x, z, height, SpawnIds.MIGRATION_SALT), npc, x, z, height, walkRadius, direction, NpcSpawnSource.Manual, note)

    fun wiki(npc: String, x: Int, z: Int, height: Int, walkRadius: Int, page: String, map: String) =
        NpcSpawnEntry(SpawnIds.wiki(page, npc, x, z, height), npc, x, z, height, walkRadius, null, NpcSpawnSource.Wiki(page, map))

    const val HANS_PAGE = "https://oldschool.runescape.wiki/w/Hans"
    const val HANS_MAP = "{{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}"

    val hansWiki get() = wiki("npc.hans", 3212, 3219, 0, 11, HANS_PAGE, HANS_MAP)
}
