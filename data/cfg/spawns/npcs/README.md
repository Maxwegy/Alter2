# NPC spawns

One file per 64x64 map region: `<regionId>.json`, where `regionId = ((x >> 6) << 8) | (z >> 6)`
(the engine's `Tile.regionId`). The server reads every file once at boot (`NpcSpawnsPlugin`) and never
writes them. Any invalid entry stops the boot with every problem logged; a missing directory only warns.

```json
{
  "schemaVersion": 1,
  "regionId": 12850,
  "spawns": [
    {
      "npc": "npc.hans",
      "x": 3221,
      "z": 3219,
      "height": 0,
      "walkRadius": 0,
      "direction": "EAST",
      "source": "manual",
      "note": "migrated from content/areas/lumbridge/npcs/HansPlugin.kt"
    }
  ]
}
```

- `npc`: an RSCM name from `data/cfg/rscm/npc.rscm`, written as the canonical (first) name for its id.
- `x`, `z`, `height` (0-3): the spawn tile; the entry must lie in the file's region.
- `walkRadius` (>= 0): how far the NPC may wander from its spawn tile.
- `direction` (optional): `NORTH_WEST`, `NORTH`, `NORTH_EAST`, `EAST`, `SOUTH_EAST`, `SOUTH`, `SOUTH_WEST` or
  `WEST`; absent means `SOUTH`, as `spawnNpc` does.
- `source`: `"manual"` for hand-written entries, or
  `{ "page": "https://oldschool.runescape.wiki/w/<Page>", "map": "<verbatim {{Map|...}}>" }` for generated ones.
- `origin` (optional): the wiki page a manual entry was edited from. `note` (optional): free text.

The same NPC may not appear twice on one tile. Entries are kept in canonical order (x, z, height, npc,
manual before wiki) with `\n` line endings; `NpcSpawnDataTests` checks all of this.

Add spawns here, not with `spawnNpc` in plugin code. Edit on a branch; `spawnItem`/`spawnObj` still live in
plugins.
