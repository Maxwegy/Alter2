# Alter2

Alter2 is a public fork of the Alter OSRS private server: Kotlin 2.0.20, JDK 17, Gradle 8.11, OSRS revision set in `gradle.properties`.

## Modules

| Module | What lives there |
|---|---|
| `game-server` | Engine: tick loop (`GameService`), world, entities, networking (rsprot), saving, plugin repository |
| `game-api` | DSLs and extensions content uses (`setCombatDef`, `player.message`, ...) |
| `game-plugins` | Content plugins (`content/`), services (`service/`), and the data-layer adapters (`content/infrastructure/`) |
| `alter-data` | Pure data layer: wiki sync, snapshot, drop math, missing-content model. **No engine imports** |
| `dev-cockpit` | Dev Cockpit: Ktor API + Vue UI on 127.0.0.1:43600; inbox, server control, audit log (SQLite in `data/cockpit/`) |
| `util` | Shared helpers, generated `BuildInfo`, `RevisionGuard` |
| `plugins/filestore`, `plugins/rscm`, `plugins/tools` | Vendored OpenRune cache, RSCM name→id maps, offline cache tools |

The server runs with `game-server/` as its working directory, so data paths are `../data/...`.

## Build and test

- `./gradlew build` (CI runs exactly this). `./gradlew :module:test --tests "pkg.ClassTests"` for one class.
- The OSRS cache (`data/cache`) is not committed; stage it with `cacheStage` and copy it in. Map squares are unencrypted from revision 237 on, so no `xteas.json` is needed. Tests that need the cache skip via JUnit `Assume`.
- Gradle must run on JDK 17. If the default `java` is older, set `JAVA_HOME` to a JDK 17.

## Data and cache tools

- `./gradlew :alter-data:wikiSync [-PwikiArgs="--offline|--refresh"]`: regenerate `data/cfg/wiki` from the OSRS Wiki; report in `data/reports/`.
- `./gradlew :alter-data:spawnSync [-PspawnArgs="--offline|--refresh"]`: regenerate the wiki entries in `data/cfg/spawns/npcs` from the infobox `{{Map}}` templates, keeping every `manual` entry; needs `data/cache`; report `data/reports/spawn-sync-<ts>.{json,md}`. Never touches `data/cfg/wiki`; `::wikisync` does not run it. `-PspawnArgs="--apply-edits"` instead applies the in-game spawn edits in `data/run/spawn-edits.jsonl` to the region files (offline, no cache; an edited wiki entry becomes `manual` with `origin`; unmatched edits are reported, not guessed); the outbox is kept as `spawn-edits.applied-<ts>.jsonl`; report `data/reports/spawn-apply-edits-<ts>.{json,md}`.
- `./gradlew :plugins:tools:cacheStage -PcacheArgs="--build N | --latest"`: download and verify a cache into `data/cache-staging/` (never `data/cache`).
- `./gradlew :plugins:tools:cacheDryRun -PcacheArgs="<staged dir> <build>"`: what our decoders and RSCM names would do with that cache.
- `./gradlew :plugins:tools:gamevalDump -PcacheArgs="<cache dir>"`: dump the gameval name tables (index 24, revision 241+) and compare them with `data/cfg/rscm`.
- `./gradlew :plugins:tools:rscmGenerate -PcacheArgs="<cache dir> <build> [--commit]"`: generate RSCM tables (gameval names + aliases for every committed name) and a migration report; `--commit` writes `data/cfg`.
- `./gradlew :plugins:tools:interfaceDump -PcacheArgs="<cache dir> <interface ids...>"`: dump interface components (gameval names, ops, text) to `data/reports/interfaces/<id>.md`; use it before binding a button on a new revision.
- In game (dev power): `::missing`, `::wikinpc`, `::wikidrops`, `::dropsim`, `::wikiitem`, `::wikisync`, `::reloadnpcs`, `::reloadconsumables`, `::reloadspecials`, `::reloadresources`; on the NPC on your tile: `::spawninfo`, `::setwander <n>`, `::setdirection <DIR>` (they change the live NPC and append to `data/run/spawn-edits.jsonl`).
- `data/cfg/npcs/overrides/*.yml`: per-field NPC combat overrides keyed by RSCM name (format in its `README.md`), laid over wiki and hand-written defs; `::reloadnpcs` re-reads them off the game thread and re-applies changed defs to live NPCs (`::wikisync` and `POST /wiki/reload` do the same).
- `data/cfg/consumables/consumables.json`: food and potions (heal, boosts, drains, restores, run energy/stamina, antipoison, antivenom, antifire, delays, expiry messages), hand-maintained with a wiki URL on every entry (the cockpit's consumable enricher appends entries from `enrich.inv_op` cards); `ConsumablesDataTests` keeps it resolvable. Plugins carry no numbers or strings.
- `data/cfg/spawns/npcs/<regionId>.json`: every NPC spawn, one file per region (`regionId = ((x >> 6) << 8) | (z >> 6)`), format in its `README.md`. Read once at boot by `NpcSpawnsPlugin`, never written by the server (in-game edits go to the `data/run/spawn-edits.jsonl` outbox, applied by `spawnSync --apply-edits`); a bad entry stops the boot. Each entry's `source` is `"manual"` or the wiki page plus its verbatim `{{Map}}`. Add spawns here, never with `spawnNpc` in plugins; `NpcSpawnDataTests` checks the files.
- `data/cfg/combat/special_attacks.json`: special attacks (cost, hits, effects, animation/graphic, wiki URL per entry); entries with a `todo` are skipped at boot; `SpecialAttacksDataTests` keeps it resolvable.
- `data/cfg/resources/resource_nodes.json`: woodcutting and mining nodes (objects with their depleted objects, level, XP, success charts, depletion, respawn), the axes and pickaxes, and the mining gem pre-roll, with a wiki URL on every entry; entries, tools and tertiary blocks with a `todo` are skipped at boot; messages stay null until sourced. `ResourceNodesDataTests` keeps it resolvable, `ResourceNodesCacheTests` checks options and footprints against `data/cache`. Runtime node state (timers, depleted nodes) lives only in memory.
- `data/cfg/combat/autocast.json`: autocast spell groups, the weapons (by type and item) that may cast each, the PvP swap lock and the 241 autocast UI ids (sourced in `data/reports/autocast-241-spike.md`); `AutocastDataTests` keeps it resolvable.
- Unscripted interactions and data gaps accumulate in `data/missing_content.json` (schemaVersion 1).

## Running the server

- `scripts/alter.sh up|down [ticks]|restart [ticks]|status|logs` (`scripts/alter.ps1` on Windows); details in `docs/server-control.md`.
- The server writes `data/run/server.json` (pid, admin port, token). Its admin API (`127.0.0.1:43595`, Bearer token) serves `GET /health`, `POST /shutdown`, `POST /wiki/reload` and `GET /events`.
- Exit code 75 means "restart me"; the scripts, Docker and systemd all start the server again on it.
- Dev Cockpit: `./gradlew :dev-cockpit:run` (add `-PskipWeb` to skip the UI build), token in `data/cockpit/owner.token`; see `docs/dev-cockpit.md`. It imports `alter-data` only, never the engine.

## Rules that are not optional

1. **Never work on `main`.** Use a branch or worktree; humans merge.
2. **Revision:** only `alter.osrsRevision` / `alter.rsprotVersion` in `gradle.properties`. Use `gg.rsmod.util.BuildInfo.REVISION`; never hardcode a revision or add one to game.yml.
3. **game-server edits are minimal and deliberate:** one fix per commit, the reason in the message, a regression test, no refactors or reformatting, no new game-server dependencies.
4. **Never block the 600ms tick.** Network and disk IO run on the data layer's IO scope or a background thread; results come back via `GameService.submitGameThreadJob`.
5. **No DI framework.** Infrastructure classes take dependencies through constructors and never call `world.getService`. One bootstrap plugin wires them and registers services with `loadService()`. Content looks services up once (`onWorldInit` or `by lazy`), never per tick.
6. **alter-data stays pure.** It must not import `org.alter.game`, `org.alter.api`, `org.alter.plugins` or `net.rsprot` (`PurityTests` enforces this).
7. **Reference data:** the wiki snapshot in `data/cfg/wiki/` is committed and generated only by `wikiSync`; never hand-edit it. Hand edits go in override files (`data/cfg/npcs/overrides`, `data/cfg/drops/overrides`, `data/cfg/items/itemOverrides`). Precedence: overrides > cache > snapshot. Production never calls the wiki.
8. **Content refers to things by RSCM name** (`"npc.man_3108"`, `"item.abyssal_whip"`), not raw ids. From revision 241 the canonical names are the cache's gameval names; the 228-era names stay as generated aliases (`docs/rscm-241-migration.md`). Never hand-edit `data/cfg/rscm`; regenerate with `rscmGenerate`.
9. **Hand-written `setCombatDef` calls go in a plugin's `init {}`**, never `onWorldInit`: wiki defs fill in remaining NPCs before spawns, and override files in `data/cfg/npcs/overrides` are laid over both (only the fields they set).
10. **Fatal boot checks log and call `exitProcess(1)`.** A plain throw leaves the JVM alive (non-daemon login threads) with no port bound.
11. **NPC tools work on existing NPCs, at three levels.** Any tool or command that changes NPCs must work on NPCs that already exist, not only ones it spawns. (a) Type level: changing a definition makes every existing and future instance follow, through `data/cfg/npcs/overrides` and the other override files. (b) Instance level: one live NPC's position, wander radius, direction, effects or stats change without touching its type. (c) Persistence: changes are written back to data files, never into plugin code. Overrides reach hand-written `setCombatDef` NPCs too, and `::reloadnpcs` applies them to live NPCs.

## Where to look

- Interaction routing and fallbacks: `game-server/.../model/move/*PathAction.kt`, `.../message/handler/*Handler.kt`, `PluginRepository.execute*`
- NPC combat defs: `NpcCombatDef`, `game-api/.../dsl/NpcCombatDsl.kt`, `World.setNpcDefaults`
- NPC spawns: `data/cfg/spawns/npcs/`, `game-plugins/.../infrastructure/spawns/NpcSpawnsPlugin.kt` + `NpcSpawnsLoader.kt`, the spawn commands (`NpcSpawnCommandsPlugin`, `NpcSpawnIndexService`), model, the outbox (`SpawnEdits`, `SpawnEditOutbox`, `SpawnEditApplier`), `SpawnRules` and the `spawnSync` generator (`SpawnSync`, `SpawnGenerator`, `MapTemplateParser`, `InfoboxFields`) in `alter-data/.../spawns/`
- Drops: `game-server/.../model/weightedTableBuilder/LootTableBuilder.kt`
- Item stats: `game-server/.../service/game/ItemMetadataService.kt` (cache params + YAML overrides)
- Saving: `PlayerSaving`, `ShutdownSaver`, `PlayerAutosave`

## Roadmap

The current plan (Phase 1 data infrastructure, Phase 1.5 revision upgrade, Phase 1.6 Dev Cockpit) is tracked in the repository's planning docs and PRs. Known bugs that are out of scope for a change go on the follow-up list, not into the change.

Design notes: `docs/roadmap-pillars.md` (spawns, resource nodes, consumables, specials, social, database) and `docs/phase-1.5-cache-241.md` (the 241 cache spike). Deferred ideas, not designed: `docs/ideas.md`.
