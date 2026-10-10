package org.alter.data.spawns

/** Where a spawn entry came from: written by hand, generated from one wiki `{{Map}}`, or changed in game. */
sealed interface NpcSpawnSource {
    /** The `source.kind` value in the region files. */
    val kind: String

    /** Hand-written data; the wiki generator never changes or removes it. */
    data object Manual : NpcSpawnSource {
        override val kind: String get() = NpcSpawnFiles.KIND_MANUAL
    }

    /**
     * Generated: [page] is the full wiki URL, [map] the verbatim `{{Map|...}}` the entry came from. [mapId] and
     * [rule] are optional provenance for entries placed off the surface map (absent on surface entries).
     */
    data class Wiki(val page: String, val map: String, val rule: String? = null, val mapId: Int? = null) : NpcSpawnSource {
        override val kind: String get() = NpcSpawnFiles.KIND_WIKI
    }

    /**
     * Changed (or added) in game and applied by `spawnSync --apply-edits` at instant [at]. [page] and [map] are the
     * wiki source the entry was generated from before its first edit (absent for edited manual entries and adds).
     * The generator treats it like a manual entry.
     */
    data class Edit(val at: String, val page: String? = null, val map: String? = null) : NpcSpawnSource {
        override val kind: String get() = NpcSpawnFiles.KIND_EDIT
    }
}

/**
 * One NPC spawn. [id] is its stable id ([SpawnIds]), [npc] an RSCM name (`npc.hans`), [x]/[z]/[height] the spawn
 * tile, [walkRadius] how far it may wander, [direction] the engine `Direction` name it faces (absent: the engine
 * default); [note] is free text for humans.
 */
data class NpcSpawnEntry(
    val id: String,
    val npc: String,
    val x: Int,
    val z: Int,
    val height: Int,
    val walkRadius: Int,
    val direction: String? = null,
    val source: NpcSpawnSource,
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
