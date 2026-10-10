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

Wiki entries are generated: `./gradlew :alter-data:spawnSync` rewrites every entry with a wiki `source` from
the infobox `{{Map}}` templates and never changes a `manual` one. To change a generated spawn, edit it into a
`manual` entry (with `origin` set to the page); a manual entry for the same npc and height within the larger
walk radius makes the generator drop the wiki one.

In game, `::spawninfo`, `::setwander <n>` and `::setdirection <DIR>` (dev power) act on the NPC on your tile: they
change the live NPC and append one line to the runtime outbox `data/run/spawn-edits.jsonl`, never to these files:

```json
{"at":"2026-10-10T12:00:00Z","npc":"npc.hans","from":{"x":3221,"z":3219,"height":0,"walkRadius":0,"direction":"EAST"},"to":{"x":3221,"z":3219,"height":0,"walkRadius":5,"direction":"EAST"},"regionId":12850,"source":"manual"}
```

`to` is `null` for a removal; `source` is `"manual"` or the wiki page. `./gradlew :alter-data:spawnSync
-PspawnArgs="--apply-edits"` (offline) applies the outbox in order: it matches each line on `(npc, from.x, from.z,
from.height)`, moves the entry to another file when its region changes, deletes it on `null`, and rewrites an
edited wiki entry as `manual` with `origin` set to its page. Lines that match nothing (or would put the same NPC
twice on a tile) are listed in `data/reports/spawn-apply-edits-<ts>.md` and skipped. The applied outbox is kept as
`spawn-edits.applied-<ts>.jsonl`.

Add spawns here, not with `spawnNpc` in plugin code. Edit on a branch; `spawnItem`/`spawnObj` still live in
plugins.
