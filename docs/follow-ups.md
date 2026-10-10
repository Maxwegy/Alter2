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
- Phase 1.5 is done: revision 241, rsprot 1.0.0-ALPHA-20261001, gameval RSCM tables with 228 aliases, no XTEA (see `docs/phase-1.5-cache-241.md`). Left over: a login smoke test with a 241 client was not possible without one (boot and `/health` were verified); the 876 unresolved and 192 clashing 228 names were dropped unreviewed (listed in `docs/rscm-241-migration.md`); `RSCM`'s reverse lookup is still O(n) per call; the meaning of flag-only opcodes NPC 129, loc 94 and item 9 is unknown.
