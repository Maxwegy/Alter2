# Alter2

Alter2 is a public fork of the Alter OSRS private server: Kotlin 2.0.20, JDK 17, Gradle 8.11, OSRS revision set in `gradle.properties`.

## Modules

| Module | What lives there |
|---|---|
| `game-server` | Engine: tick loop (`GameService`), world, entities, networking (rsprot), saving, plugin repository |
| `game-api` | DSLs and extensions content uses (`setCombatDef`, `player.message`, ...) |
| `game-plugins` | Content plugins (`content/`), services (`service/`), and the data-layer adapters (`content/infrastructure/`) |
| `alter-data` | Pure data layer: wiki sync, snapshot, drop math, missing-content model. **No engine imports** |
| `util` | Shared helpers, generated `BuildInfo`, `RevisionGuard` |
| `plugins/filestore`, `plugins/rscm`, `plugins/tools` | Vendored OpenRune cache, RSCM name→id maps, offline cache tools |

The server runs with `game-server/` as its working directory, so data paths are `../data/...`.

## Build and test

- `./gradlew build` (CI runs exactly this). `./gradlew :module:test --tests "pkg.ClassTests"` for one class.
- The OSRS cache (`data/cache`, `data/xteas.json`) is not committed. Tests that need it skip via JUnit `Assume`.
- Gradle must run on JDK 17. If the default `java` is older, set `JAVA_HOME` to a JDK 17.

## Rules that are not optional

1. **Never work on `main`.** Use a branch or worktree; humans merge.
2. **Revision:** only `alter.osrsRevision` / `alter.rsprotVersion` in `gradle.properties`. Use `gg.rsmod.util.BuildInfo.REVISION`; never hardcode a revision or add one to game.yml.
3. **game-server edits are minimal and deliberate:** one fix per commit, the reason in the message, a regression test, no refactors or reformatting, no new game-server dependencies.
4. **Never block the 600ms tick.** Network and disk IO run on the data layer's IO scope or a background thread; results come back via `GameService.submitGameThreadJob`.
5. **No DI framework.** Infrastructure classes take dependencies through constructors and never call `world.getService`. One bootstrap plugin wires them and registers services with `loadService()`. Content looks services up once (`onWorldInit` or `by lazy`), never per tick.
6. **alter-data stays pure.** It must not import `org.alter.game`, `org.alter.api`, `org.alter.plugins` or `net.rsprot` (`PurityTests` enforces this).
7. **Reference data:** the wiki snapshot in `data/cfg/wiki/` is committed and generated only by `wikiSync`; never hand-edit it. Hand edits go in override files (`data/cfg/npcs/overrides`, `data/cfg/drops/overrides`, `data/cfg/items/itemOverrides`). Precedence: overrides > cache > snapshot. Production never calls the wiki.
8. **Content refers to things by RSCM name** (`"npc.man_3108"`, `"item.abyssal_whip"`), not raw ids.
9. **Hand-written `setCombatDef` calls go in a plugin's `init {}`**, never `onWorldInit`: wiki defs fill in remaining NPCs before spawns.
10. **Fatal boot checks log and call `exitProcess(1)`.** A plain throw leaves the JVM alive (non-daemon login threads) with no port bound.

## Where to look

- Interaction routing and fallbacks: `game-server/.../model/move/*PathAction.kt`, `.../message/handler/*Handler.kt`, `PluginRepository.execute*`
- NPC combat defs: `NpcCombatDef`, `game-api/.../dsl/NpcCombatDsl.kt`, `World.setNpcDefaults`
- Drops: `game-server/.../model/weightedTableBuilder/LootTableBuilder.kt`
- Item stats: `game-server/.../service/game/ItemMetadataService.kt` (cache params + YAML overrides)
- Saving: `PlayerSaving`, `ShutdownSaver`, `PlayerAutosave`

## Roadmap

The current plan (Phase 1 data infrastructure, Phase 1.5 revision upgrade, Phase 1.6 Dev Cockpit) is tracked in the repository's planning docs and PRs. Known bugs that are out of scope for a change go on the follow-up list, not into the change.
