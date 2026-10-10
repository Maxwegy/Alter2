# Follow-up list

Known problems that were out of scope for the change that found them. Each is a candidate for its own small PR; none should be fixed "while you're in there".

## Server and launcher
- A force-killed game server (`taskkill /F`, `kill -9`) can't run its shutdown hook, so `data/run/server.json` is left behind. `scripts/alter` and the cockpit check whether the pid is alive before trusting the file, so nothing misbehaves, but the stale file should be cleaned up at the next start and the scripts' `status` should say "stale run file".
- `WorldListService.bindNet` blocks forever.
- `Service.terminate` is never called.
- `PlayerDetails.displayNames` is modified concurrently.
- The Mongo save path hits `TODO()` at boot.
- `Client.logPackets` is on for every player.
- `GameService` queue counters for players and npcs always read 0.
- `extractDependencies` and the shadow distribution paths still assume the old `game` module name.

## Interactions
- `IfButtonTHandler:77-82` has an inverted condition: it prints "Unhandled" when the catch-all did handle the item.
- Inventory op numbering vs `onItemOption` index+1: `InventoryPlugin` passes the client's op straight to `executeItem`, and `onItemOption(String)` binds the option's index + 1, so the first option ("Eat"/"Drink") is op 1; the consumables plugin binds 55 items this way without a skip. Still not exercised with a client.
- `resetInteractions` (`Pawn.kt:497`) doesn't clear `INTERACTING_ITEM`/`OBJ` attributes, so stale targets leak.
- `OpNpcTHandler` trusts the client's `selectedObj` without validating it against the inventory, uses the raw npc id and does `println` debug output.
- NPC op 2 always attacks (`OpNpcHandler:20`), so the unhandled-interaction hook never sees it.
- Item-on-NPC never walks to the npc.

## Special attacks (Phase 3)
- TODO entries in `data/cfg/combat/special_attacks.json`, skipped at boot: dragon claws (the 4-hit sequence), dragon scimitar (the PvP prayer lock), dragon battleaxe (the drain base), dragon sword (the prayer bypass), dragon 2h sword (multi-target), dragon spear (pushback), magic shortbow and (i) (the ranged path; the (i) costs 50%), dark bow (ranged minimum hits).
- `MeleeCombatFormula` has no defence-style override, so specials that roll against slash (AGS, BGS, SGS, DDS, whip, halberd, Saradomin sword) or stab (arclight) defence roll against the current style.
- Special attack hits grant no melee experience (the old plugins did not either); only the Saradomin sword's magic hit gives its 2 Magic XP per damage.
- Not in the batch: Lightbearer and the Surge potion (energy), the granite maul's ornate handle (50%) and cosmetic variants, the 3rd age axe, the halberd's multi-target sweep and directional graphic, the Saradomin sword's graphic and its splash on Protect from Magic, Excalibur's forced chat, the Tekton and Elite Black Knight exceptions.
- Unverified against a 241 client: the sound ids (the old plugins' values, `verify: true`), animation and graphic appearance (and whether a graphic belongs on the target), the dragon pickaxe's animation (7138 `rockknocker` vs 2661 `dragon_pickaxe_anim`), the varp 300 scale and the 593:39 / 160:36 component ids (now taken from the 241 cache). Sounds play to the attacker only (`playSound`), not as area sounds.
- The bar switches off on every weapon equip and on logout (kept as it was).
- 241 component ids on interfaces 593 and 160 were corrected from the cache (`data/reports/combat-orbs-241-components.md`, checked by `CombatOrbsComponentsTests`). Left open:
  - none of the new ids was exercised with a 241 client;
  - the world map orb (160:55) has four script-labelled ops; which op is "Floating World Map" and which "Fullscreen" (the plugin's `opt != 1` test) is not settled by the cache;
  - whether a client script already sets the 593 `title`/`category` text, making `sendWeaponComponentInformation`'s writes redundant;
  - other interfaces bound with 228-era ids (bank, settings 116, quick prayers 77, the world map 595, the toplevel child ids in `InterfaceDestination`, ...) may share the shift; unverified. `./gradlew :plugins:tools:interfaceDump -PcacheArgs="../data/cache <id>..."` lists any interface's components.

## Autocast (Phase 3)
- Not modelled:
  - the other autocast lists of interface 201: crumble undead, magic dart, Iban blast, the god spells, Arceuus. Script 243 picks them by varp 664 keys such as `slayer_staff`, `ibanstaff`, `sotd`, `kodai_wand` and `barrows_ahrim_weapon`, partly by spellbook, and those spells have no `CombatSpell` entry;
  - Ahrim's staff's degraded and ornament variants;
  - weapons the wiki does not name.
- Varp 664: the server sends -1 (the standard list) on the standard spellbook and 4675 (the Ancients list) on Ancients. The real server's rule for this varp is not in the cache. The menu opens only for the group of the current spellbook.
- Whether a spellbook change clears autocast is not on the wiki page, so nothing clears it.
- The restore order on a weapon change prefers the group of the current spellbook (a design choice).
- Varbit 2668 is set from the button that opened the menu and cleared with the selection. A restored selection keeps the current flag.
- Not verified with a 241 client:
  - opening 201 in the combat tab slot and reopening 593 after a choice;
  - the if_setevents range 0..58 on 201:1.

## NPC spawns (Phase 2)
- Decision pending (user), D1: whether to reduce `{{Map}}` polygons to a spawn tile and radius; until then the generator skips them and reports them.
- Decision pending (user), D2: an allowlist of non-surface `mapID`s; until then non-zero `mapID`s are skipped and reported.
- The absent-`mtype` question is settled against the plan's default: Module:Map creates no feature without an `mtype` (only the view centre), and `action=parse` of `{{Map|3212,3219}}` renders no overlay, so `spawnSync` skips such templates as `noMtype`.
- `spawnSync` reads only the infobox `map`/`mapN` values. `{{Map}}` templates elsewhere on a page, anonymous inline features (`|mtype:dot,...`) and the wiki's `Bucket:Map` (Module:Map stores every map's GeoJSON there) are not used; the Bucket could replace wikitext parsing.
- The cockpit `enrich.spawn` enricher and `spawn` scaffold (the planner's optional PR 4) are not scheduled.
- Spawn tiles are used as written: no nearest-walkable-tile adjustment, and no `Assume`-guarded walkability test over the region files yet.
- No spawn hot reload; a change to `data/cfg/spawns/npcs` needs a restart.
- Facing direction and respawn timing are not taken from the wiki; per-type spawn defaults do not exist.
- `data/cfg/spawns/item_spawns.yml` is tracked but listed in `data/cfg/spawns/.gitignore`, and nothing reads it.
- The 115 migrated `manual` spawns keep the old plugins' values, including odd ones worth a review against the wiki: `npc.giant_spider` resolved to id 2477 (canonical `npc.sos_pest_giantspider1`), three Lumbridge men/women on height 1, the goblins with godwars/war-themed ids near Lumbridge, and the Barrows brothers spawned five times each above ground.
- `::shiftnpc` and `::removespawn` (Phase 2 PR 3) are not built: both need `World.remove`, which never releases the NPC's rsprot avatar, so the removed NPC stays visible to clients and the next `World.spawn` that reuses its index throws in `NpcAvatarRepository.getOrAlloc` (`::removenpc` has the same problem today). They wait for the Phase 5 GS-1 game-server fix; the outbox format and `--apply-edits` already handle moves and removals.
- An edited wiki entry becomes `manual` with `origin`, but `spawnSync` drops its regenerated wiki twin only while the two overlap (same npc and height within the larger walk radius). Moving an edited entry further away brings the wiki twin back on the next run; the generator could also drop wiki entries whose page is a same-npc manual entry's `origin`.
- `--apply-edits` only matches existing entries; an add edit (`from: null`, planned for Phase 5 `::spawnnpc --persist`) is not supported yet.
- `::setdirection` sets `lastFacingDirection` and sends one face-coordinate update; a respawn after death does not re-send the facing. Not exercised with a 241 client.

## Commands and privileges
- `::qutest`, `::gc`, `::heap` and `::randbank` have no privilege check.

## Data and cache
- `ItemMetadataService`: one `try` around a parallel file walk, so the first bad file stops the rest. It should load sorted, sequentially, with per-file errors.
- `DumpEntityIdService` writes `npcs.rscm` and ignores `output-path`.
- `PackConfig` writes ITEMS into archive 6.
- `WeaponCategory` ids disagree with `WeaponType`. The 241 cache adds category 2294 (first on item 32712); unknown categories now fall back to unarmed with one warning instead of aborting the item load, but the table itself is still incomplete.
- `ItemMarketValueService` has an O(n²) boot copy and is never registered.
- Consumables (Phase 3b/3c): TODO: the stamina expiry chat line has no wiki or cache source, so `messages.staminaExpired` is null and no message is sent until one is found; the antifire "about to expire" warning is sent 25 ticks before expiry (wiki: about 15 seconds). None of it is exercised with a 241 client yet.
- Poison and venom (Phase 3c): poison hits every 25 ticks (`POISON_TICK_DELAY` in `PoisonPluginPlugin`) where the wiki Poison page says 30. `AttributeSerialisation.kt:12` (game-server) rebuilds every saved attribute as `AttributeKey<Any>(key)` with `resetOnDeath = false`, while `AttributeKey.equals` compares `resetOnDeath`, so `poison_ticks_left` and `venom_damage` (both `resetOnDeath = true`) are saved but probably never found again after login (out of scope: a game-server fix). Not modelled: NPC venom pausing out of combat and NPC immunity until death; `NpcCombatDef.venomChance`/`poisonChance` are set by the DSL but nothing reads them, so NPCs never poison or envenom on hit; antidote++'s venom immunity (only when not envenomed); extended anti-venom+; whether a second envenoming restarts the sequence (kept as is). `Pawn.venom` refuses while poison-immune (antipoison timer, the helms in `Poison.isImmune`); the helm recolours' immunity (tanzanite, magma) is to be confirmed against the Serpentine helm page. The venom HP-orb value (varp 102 = 1,000,000) is unverified. `Varbit.STAMINA_DURATION` (12362) is misnamed: its gameval name is `buff_stamina_duration_disabled`; the units of varbit 24 (`stamina_duration`) are unknown, so it is not set.
- Dev cockpit `RscmNames.name(type, id)` keeps the last name listed for an id. In `item.rscm` that is the generated alias (`antivenom4_12913`), not the canonical gameval name (`antivenom+4`), so a scaffold for anti-venom+ or any other id with an alias emits the alias. It should prefer the first name, as `RSCM.initRSCM`'s first-wins reverse lookup does.
- Phase 1.5 is done: revision 241, rsprot 1.0.0-ALPHA-20261001, gameval RSCM tables with 228 aliases, no XTEA (see `docs/phase-1.5-cache-241.md`). Left over: a login smoke test with a 241 client was not possible without one (boot and `/health` were verified); the 876 unresolved and 192 clashing 228 names were dropped unreviewed (listed in `docs/rscm-241-migration.md`); `RSCM`'s reverse lookup is still O(n) per call; the meaning of flag-only opcodes NPC 129, loc 94 and item 9 is unknown.
