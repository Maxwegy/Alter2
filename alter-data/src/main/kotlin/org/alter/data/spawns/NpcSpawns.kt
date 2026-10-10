package org.alter.data.spawns

/** Where a spawn entry came from: written by hand, or generated from one `{{Map}}` on a wiki page. */
sealed interface NpcSpawnSource {
    /** Hand-written (or edited) data; the wiki generator never changes or removes it. */
    data object Manual : NpcSpawnSource

    /** [page] is the full wiki URL, [map] the verbatim `{{Map|...}}` the entry was generated from. */
    data class Wiki(val page: String, val map: String) : NpcSpawnSource
}

/**
 * One NPC spawn. [npc] is an RSCM name (`npc.hans`), [x]/[z]/[height] the spawn tile, [walkRadius] how far it
 * may wander, [direction] the engine `Direction` name it faces (absent: the engine default). [origin] is the
 * wiki page a manual entry was edited from; [note] is free text for humans.
 */
data class NpcSpawnEntry(
    val npc: String,
    val x: Int,
    val z: Int,
    val height: Int,
    val walkRadius: Int,
    val direction: String? = null,
    val source: NpcSpawnSource,
    val origin: String? = null,
    val note: String? = null,
) {
    val regionId: Int get() = SpawnRules.regionId(x, z)

    /** Two entries with the same key would spawn the same NPC on the same tile twice. */
    val key: Key get() = Key(npc, x, z, height)

    data class Key(val npc: String, val x: Int, val z: Int, val height: Int)
}

/** The contents of `data/cfg/spawns/npcs/<regionId>.json`. */
data class NpcSpawnFile(
    val regionId: Int,
    val spawns: List<NpcSpawnEntry>,
    val schemaVersion: Int = NpcSpawnFiles.SCHEMA_VERSION,
)
