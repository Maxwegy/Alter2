# NPC combat overrides

Hand-written corrections that win over the cache and the wiki snapshot. One YAML file per NPC or group,
keyed by RSCM name. Any field you leave out keeps the merged cache/wiki value.

```yaml
npcs: [npc.abyssal_demon_415, npc.abyssal_demon_416]
hitpoints: 150
attack: 97
strength: 67
defence: 135
magic: 1
ranged: 1
attackSpeed: 4
respawnTicks: 12
aggressive: false
```

Overrides also apply to NPCs given a definition with `setCombatDef` in a plugin's `init {}` (for example
`npc.cow`, the Barrows brothers, `npc.king_black_dragon`): only the fields the file sets change, and the plugin's
animations, sounds, bonuses, species and immunities are kept. `::wikinpc` then reports
`hand-written plugin + override (<fields>)`.

Files are read in name order; when two files name the same NPC, the later file wins. A file that does not parse
or names an unknown NPC is skipped and logged. `::reloadnpcs` (or `::wikisync`, `POST /wiki/reload`) re-reads
this folder and applies the result to live NPCs whose definition changed (at full hitpoints); deleting a file and
reloading restores the plugin's or the wiki's values. Tests never write here; they use temporary folders.
