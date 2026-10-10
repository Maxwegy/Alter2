# Autocast: 241 cache spike (Phase 3, PR 2 step 0)

Read-only spike against the revision 241 cache (OpenRS2 2735), in `data/cache-staging/241-2735` and `data/cache` (same manifest; the gameval tables for interfaces 160, 201 and 593 were byte-identical in both). Nothing was written to either cache. It was done with throwaway readers on top of `plugins/filestore` (`Cache.load`): an if3 component decoder (index 3), a cs2 disassembler (index 12, 9919 of 9973 scripts parsed), an enum reader (index 2, group 8), a varbit reader (index 2, group 14) and the gameval tables (index 24, group 14 holds the component names of each interface). The readers were not kept.

## Results

| Question | Answer | Status |
|---|---|---|
| 593 component that opens the menu for normal autocast | **593:28** `autocast_normal`, op1 "Choose spell" | settled |
| 593 component that opens the menu for defensive autocast | **593:23** `autocast_defensive`, op1 "Choose spell" | settled |
| Interface 201 spell buttons | dynamic children of **201:1** `spells`: child 0 is "Cancel", child *n* (1..58) is the spell whose autocast id is *n* | settled |
| Which spells the menu shows | decided by **varp 664** (an item id, or -1) and varbit 4070 (spellbook) in script 243 | settled for the standard and Ancient lists (below) |
| Selection storage the client reads | **varbit 276** `autocast_spell` (varp 108 bits 1..31) | settled |
| varp 4720 `autocast_spell_obj` | read and written by no client script | settled: not needed |
| Defensive flag | **varbit 2668** `autocast_defmode` (varp 439 bit 8) | settled |
| varbit 275 `autocast_set` (varp 108 bit 0) | read and written by no client script | settled: not needed by the client |

## Evidence

### Interface 593 (`combat_interface`)

Gameval component names (index 24, group 14, file 593): `22 autocast_buttons`, `23 autocast_defensive`, `24 defensive_container`, `28 autocast_normal`, `29 normal_container`, `39 special_attack`.

Component definitions (index 3):
- 593:23: layer in 22, click mask 0x2 (op1 transmitted), ops `[Choose spell]`.
- 593:24: layer in 22, the same size as 23; its children are 25 (the spell icon) and 26 (sprite 760, the shield).
- 593:25: graphic in 24; `onLoad` / `onVarTransmit` = script 329 with args (593:25, **1**); var triggers varp 108 and 439.
- 593:28: layer in 22, click mask 0x2, ops `[Choose spell]`.
- 593:29: layer in 22, the same size as 28; its children are 30 (the spell icon) and 31 (the text "Spell").
- 593:30: graphic in 29; script 329 with args (593:30, **0**).

Script 329 (component, ?, mode):
- reads `get_varbit 276` and looks it up in enum 1986 (int → obj) to draw the spell's icon;
- compares `get_varbit 2668` with its third argument and highlights the icon when they are equal.

So the defensive slot (25, arg 1) lights up when varbit 2668 = 1 and the normal slot (30, arg 0) when it is 0. Both slots show the same spell (varbit 276).

Both "Choose spell" components have op1 in their static click mask, so no `if_setevents` is needed for them.

### Interface 201 (`autocast`)

Gameval names: `0 universe`, `1 spells`, `2 info`, `3 com_3`, `4 com_4`, `5 com_5`. The 201 definitions hold no buttons.

201:0 `onLoad` is script 235 with (201:1, 201:2). Script 235 calls script 2098 and registers 2097 (a wrapper around 2098) as a varp-664 transmit listener.

Script 2098 (spells layer, info layer):
- `cc_deleteall` on 201:1.
- `cc_create(201:1, text, 0)` with `cc_setop(1, "Cancel")`.
- Then for i = 1 to **58**:
  - `cc_create(201:1, graphic, i)`
  - `(x, y) = script 243(i, get_varp 664)`
  - `obj = enum 1986[i]`
  - When x, y and obj are all ≠ -1, the child is placed at (x, y) and gets `cc_setop(1, oc_param(obj, 601))` (the spell name as op1).
  - Otherwise it is hidden.

So an IF_BUTTON on 201:1 arrives with **sub = the autocast id** (0 = Cancel). The children are created by script and have no click mask of their own, so the server must send `if_setevents(201, 1, 0..58, op1)` before the client transmits the clicks.

Script 243 (spell id, varp 664) switches on varp 664:
- **-1** → script 4512, whose switch covers ids 1–16 and 48–51: the standard elemental strikes to waves, and the surges.
- **4675** (`staff_of_zaros`, RSCM alias `item.ancient_staff`) → script 4511, which covers ids **31–46**: the Ancient rushes to barrages.
- Other keys exist for:
  - 9013 `sos_skull_sceptre`, 21276 `sos_skull_sceptre_imbued`
  - 4170 `slayer_staff`
  - 8841 `pest_void_knight_mace`
  - 1409 `ibanstaff`
  - 11791 / 22296 / 24144 (the staff of the dead family)
  - 27785 / 27788 / 27676 / 27679 (the attuned wilderness sceptres)
  - 21006 `kodai_wand`
  - 4710 `barrows_ahrim_weapon`

  Some of those keys also test varbit 4070. They give the menus for Crumble Undead, Magic Dart, Iban Blast, god spells and Arceuus. That is outside PR 2 (TODO).

The varp-664 values are item ids, so the server picks one. The real server's rule for choosing it is not in the cache. PR 2 uses:
- -1 (the standard list) on the standard spellbook;
- `item.ancient_staff` (4675, the key whose list is exactly ids 31–46) on the Ancient spellbook when the weapon may autocast Ancients.

The legacy constant `Varp.AUTOCAST_BASE_ITEM = 664` in game-api agrees with this reading.

### Spell ids: enum 1986

Enum 1986 maps autocast id → spell obj. Every one of the 36 `CombatSpell` entries matches it: the `autoCastId` → `id` pairs, 1–16 → 3273…3321, 48–51 → 21876…21879 and 31–46 → 4629…4651. The obj gameval names agree too, for example `01_wind_strike`, `75_fire_wave`, `95_fire_surge`, `50_smoke_rush`, `94_ice_barrage`.

The enum has 48 keys (1–20, 31–58). Ids 17–20 and 47 (crumble undead, magic dart, claws/flames/saradomin strike, iban blast) and 52–58 have no `CombatSpell` entry, so they are not autocastable here.

### Variables

| Variable | Varp and bits | Read by scripts |
|---|---|---|
| varbit 275 `autocast_set` | varp 108 bit 0 | none |
| varbit 276 `autocast_spell` | varp 108 bits 1..31 | 329 |
| varbit 2668 `autocast_defmode` | varp 439 bit 8 | 329, 4525, 5380 |
| varp 4720 `autocast_spell_obj` | — | none (no get or set) |
| varbit 357 `combat_weapon_category` | varp 843 bits 0..5 | 4525, 5380, 7593, 7604 |
| varbit 4070 `spellbook` | varp 439 bits 0..1 | read inside script 243 |

The server therefore keeps the selection in varbit 276 and the defensive flag in varbit 2668, as the code already did.

## Found in passing (PR 1 bindings, out of scope for PR 2)

The 241 gameval names and definitions put several existing 593/160 buttons one component off from the ids the plugins bind:

| Button | Bound by the plugins | 241 component |
|---|---|---|
| Special attack bar | 593:36 | **593:39** `special_attack`, op "Use Special Attack" (593:36 is `retaliate_text`) |
| Minimap special orb | 160:35 | **160:36** `specbutton` (160:35 is `specenergy_backing`) |
| Attack style buttons | 593:5 / 9 / 13 / 17 | 593:6 / 10 / 14 / 18 (`0`, `1`, `2`, `3`, ops `*`) |
| Auto retaliate | 593:31 | 593:32 `retaliate`, op "Auto retaliate" |

`sendWeaponComponentInformation` writes 593:2 and 593:3, which are `header` and `title`; `category` is 593:5.

These are listed in `docs/follow-ups.md` and are not changed here.

## Not verified

- No 241 client was available, so none of this was exercised end to end.
- The server-side choice of the varp 664 value is a design decision. Only the lists the -1 and 4675 keys produce are evidenced.
- The client behaviour when the server opens 201 in the combat tab slot (reopening 593 after a choice) follows the interface structure. It is not observed.
