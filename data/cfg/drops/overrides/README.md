# Drop table overrides

A YAML file here replaces the wiki snapshot's drop tables for the listed NPCs entirely. Items are RSCM
names; chances are exact `[numerator, denominator]`. Use this to fix tables the sync report flags as
`needsReview` (main-table chances that add up to more than 1).

```yaml
npcs: [npc.abyssal_demon_415, npc.abyssal_demon_416]
always:
  - { item: item.abyssal_ashes }
main:            # one roll picks at most one of these; the remainder drops nothing
  - { item: item.abyssal_whip, chance: [1, 512] }
  - { item: item.coins_995, min: 132, chance: [35, 128] }
tertiary:        # each rolled independently
  - { item: item.clue_scroll_hard, chance: [1, 128] }
```

NPCs whose hand-written combat definition has a non-empty `drops {}` block ignore both overrides and the snapshot.
