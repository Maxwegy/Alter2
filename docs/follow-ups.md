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
- Inventory op numbering vs `onItemOption` index+1: string-bound options such as EatingPlugin's might never fire. Verify with the packet log.
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
- `WeaponCategory` ids disagree with `WeaponType`.
- `ItemMarketValueService` has an O(n²) boot copy and is never registered.
- Phase 1.5 (241 cache): the reference-table `0x4` lengths flag that broke the vendored filestore and displee 7.1.0 is fixed (spike, see `docs/phase-1.5-cache-241.md`); still open is the decoder opcode catch-up for revisions 229-241 (NPC/item/object opcodes listed there), which is the first task of the Phase 1.5 PR.
