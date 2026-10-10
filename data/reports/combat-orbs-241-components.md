# Interfaces 593 and 160: server bindings vs the revision 241 cache

Read-only, against `data/cache` (revision 241, OpenRS2 2735). Evidence from `./gradlew :plugins:tools:interfaceDump -PcacheArgs="../data/cache 593 160"`: gameval component names (index 24, group 14) and if3 definitions (index 3: type, parent, click mask, ops). The full dumps are written to `data/reports/interfaces/593.md` and `160.md`. `CombatOrbsComponentsTests` (plugins/tools) checks every row below against the cache.

The click mask bit *n* means op *n* is transmitted to the server; `*` is an op whose text the client sets by script.

## Changed

| Binding | Old | New | 241 evidence (new) | What the old id is on 241 |
|---|---|---|---|---|
| Special attack bar (`SpecialAttacksPlugin`) | 593:36 | **593:39** | `special_attack`, layer in 38 `sp_attackbar`, mask 0x2, op1 "Use Special Attack" | `retaliate_text`, text, no ops |
| Special attack orb (`SpecialAttacksPlugin`) | 160:35 | **160:36** | `specbutton`, layer in 34 `orb_specenergy`, mask 0x2, op1 `*` | `specenergy_backing`, graphic, no ops |
| Attack style 0 (`AttackTabPlugin`) | 593:5 | **593:6** | `0`, layer, mask 0x2, op1 `*`; children `0_back`, `0_icon`, `0_text` | `category`, text |
| Attack style 1 | 593:9 | **593:10** | `1`, layer, mask 0x2, op1 `*` | `0_text`, text |
| Attack style 2 | 593:13 | **593:14** | `2`, layer, mask 0x2, op1 `*` | `1_text`, text |
| Attack style 3 | 593:17 | **593:18** | `3`, layer, mask 0x2, op1 `*` | `2_text`, text |
| Auto retaliate (`AttackTabPlugin`) | 593:31 | **593:32** | `retaliate`, layer, mask 0x2, op1 "Auto retaliate" | `com_31`, the "Spell" text of the autocast slot |
| Weapon name (`sendWeaponComponentInformation`) | 593:2 | **593:3** | `title`, text in 2 `header` | `header`, a layer (cannot hold text) |
| Weapon category text (`sendWeaponComponentInformation`) | 593:3 | **593:5** | `category`, text | `title`, so the category line was written into the title |
| Run orb (`RunEnergyPlugin`) | 160:27 | **160:28** | `runbutton`, layer in 26 `orb_runenergy`, mask 0x2, op1 "Toggle Run" | `runenergy_backing`, graphic, no ops |
| Quick prayers (`PrayersPlugin`) | 160:19 | **160:20** | `prayerbutton`, layer in 18 `orb_prayer`, mask 0x6, op1 `*`, op2 "Setup" | `prayer_backing`, graphic, no ops |
| XP drops (`XpDropsPlugin`) | 160:5 | **160:6** | `xp_drops`, graphic, mask 0x6, op1 `*`, op2 "Setup" | `cr_icon` (content recommendation), graphic, no ops |
| World map orb (`WorldMapPlugin`) | 160:53 | **160:55** | `worldmap`, graphic in 49 `orb_worldmap`, mask 0x1e, ops 1-4 `*` | `wiki_icon_graphic`, graphic, no ops |

The world map orb moved by two: 160 gained the wiki (50, 52, 53) and content recommendation (2-5, 48) components ahead of it.

## Unchanged (already correct)

| Binding | Id | 241 evidence |
|---|---|---|
| Autocast "Choose spell" (normal) | 593:28 | `autocast_normal`, mask 0x2, op1 "Choose spell" (cache-sourced in `autocast.json`) |
| Autocast "Choose spell" (defensive) | 593:23 | `autocast_defensive`, mask 0x2, op1 "Choose spell" |

## Not bound by the server (for reference)

160:4 `cr_button` (op1), 160:9 `healthbutton` (op1 "Cure"), 160:46 `store_button` (ops 1-2), 160:52 `wiki_icon` (op10 "Search"), 593:37 `set_effect` (op1 "Toggle set effect"), 593:46 `switch_button` (op1 "View").

## Not verified

- No 241 client: none of the new ids was clicked end to end.
- The ops shown as `*` get their text from client scripts; which op number the world map orb sends for "Floating World Map" vs "Fullscreen" (the plugin's `opt != 1` test) is not settled by the cache.
- Whether a client script also sets the 593 title/category text (so the server writes are redundant) was not checked.
- Interfaces other than 593 and 160 (including the toplevel child ids in `InterfaceDestination`) were not checked and may share the shift.
