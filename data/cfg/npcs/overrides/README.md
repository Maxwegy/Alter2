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

NPCs given a definition with `setCombatDef` in a plugin's `init {}` are left alone entirely.
