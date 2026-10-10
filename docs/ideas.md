# Ideas parking lot

Deferred ideas, recorded so nothing is lost. Nothing here is designed or scheduled. An idea leaves this page only when the build matrix promotes its row and a plan is written. Each section lists what the idea is, why it was deferred, the rule conflicts to resolve before it can be designed, and its matrix row.

Rules referred to below are the numbered rules in `CLAUDE.md`. "Runtime state never goes in `data/cfg`" is a standing project decision: the server reads `data/cfg` at boot and never writes it while running.

## 1. Behaviour chains (data-driven NPC behaviour)

**What:** NPC behaviour assembled from data-driven nodes instead of per-NPC plugin code. Proposed traits:

- `TRAIT_TIMID`: flees from well-geared attackers and breaks line of sight.
- `TRAIT_CRAFTY`: times counter-attacks to the player's weapon recovery.
- `TRAIT_SMART`: targets the participant with the lowest Hitpoints or the weakest defence.

These would sit on a node-chain schema with nodes such as `PERCEPTION_SCANNER`, `COMBAT_KITER` and `DYNAMIC_REGRID` (call for help).

**Why deferred:** these are not OSRS mechanics. They belong to the Monster Maker idea (section 5), which is past the autonomous-build stop point.

**Rule conflicts to resolve first:**
- Decision logic must run on the game thread inside the 600 ms tick budget (rule 4), so perception and pathing scans need a cost bound.
- Node chains must be data, not plugin code, and must work on existing NPCs at the type, instance and persistence levels (rule 11).
- Any per-NPC runtime state (who it fled from, a call-for-help cooldown) is runtime state and cannot live in `data/cfg`.
- Hooking AI into combat and movement may need engine (game-server) changes beyond a minimal tested fix (rule 3); that makes it a manual gate.

**Matrix row:** Idea, "Behaviour chains, reactive resource configs, automated merges", under Monster Maker / MRE.

## 2. Spectacle: selective visibility

**What:** NPCs that only a target or a party can see, such as a group-only boss, without instancing the area.

**Superseded use case:** the per-player "ghost" (a phantom that stalks and talks to one player) is replaced by Phase 6, LLM-driven NPCs: NPCs that everyone can see, wake when a player says their name in public chat, and answer with overhead chat. Phase 6 does not need selective visibility, so nothing on the roadmap depends on this idea any more. What remains here is the technique, kept for Monster Maker content such as party-only bosses.

**Implementation note:** use rsprot's built-in specific NPCs, `NpcAvatarFactory.alloc(specific = true)` plus `npcInfo.setSpecific` and `npcInfo.clearSpecific`. Do not build a custom encoder bitmask.

**Why deferred:** its content use belongs to Monster Maker, and the game-server wrapper it needs has no regression test (rule 3).

**Rule conflicts to resolve first:**
- `net.rsprot` stays in the network layer (`.claude/rules/game-server.md`), so content needs a game-api hook rather than direct rsprot calls.
- Visibility sets are runtime state, never in `data/cfg`.
- Combat, aggression and drops must agree with visibility: a player who cannot see an NPC must not be attacked by it or loot it. Check how far that reaches into the engine before planning.

**Matrix row:** Idea, "Selective visibility (spectacle)". `::showonly` / `::showall` are part of this idea; no roadmap item builds them.

## 3. Resource micro-economy / supply chains

**What:** production nodes, such as trees, rocks and fishing spots from the Phase 4 template, that feed a shared supply-chain state. Output would then change prices, spawns or availability elsewhere.

**Why deferred:** Phase 4 delivers the resource-node template first. The economy layer on top is a separate design with no OSRS basis.

**Rule conflicts to resolve first:**
- Game state changes only on the game thread (rule 4). Supply-chain aggregation and anything that reads or writes it from IO or the cockpit must hand results back through `GameService.submitGameThreadJob`.
- The supply-chain state is runtime state, so it never goes in `data/cfg`. It needs its own store, and the database decision is JSON + autosave (Mongo stays LATER).

**Matrix row:** Idea, "Resource micro-economy", as a Phase 4 follow-on.

## 4. Automated merges and reactive resource configs

**What:**
- **Automated merges:** the cockpit or an agent merges its own work without a human step.
- **Reactive resource configs:** resource definitions that change themselves in response to play, such as respawn times tuned by demand.

**Why deferred:**
- Merges stay human-gated. Rule 1 says humans merge, and the PR workflow uses auto-merge only behind a required green `build` check on a PR a human can inspect.
- Self-modifying configs would write `data/cfg` at runtime.

**Rule conflicts to resolve first:**
- Rule 1 and the PR workflow for merges.
- "Runtime state never goes in `data/cfg`" for reactive configs. Any tuning would have to be proposed as a reviewed data change, not applied live.

**Matrix row:** Idea, "Behaviour chains, reactive resource configs, automated merges".

## 5. Monster Maker / MRE

**What:** a toolkit for authoring new monsters as data: stats, behaviour from section 1, presentation and visibility from section 2, and drops.

**Why deferred:** it is the autonomous-build stop point. It is not planned or built until the user promotes it.

**Rule conflicts to resolve first:** everything listed under sections 1 and 2. On top of that, invented monsters need ids, names and models that do not collide with the cache, because content refers to things by RSCM name (rule 8).

**Matrix row:** Idea, "Monster Maker / MRE". Depends on sections 1 and 2.

## 6. Live World Director (sandbox events)

**What:** the world as a stage for planned, opt-in events instead of a fixed map:
- Mirage spawns: NPCs that appear near a player and leave again.
- Path events: temporary barriers or blocked routes.
- Travelling caravans: groups of NPCs walking between towns over many ticks.
- Phased areas: a player or group sees a changed version of a place.

**What already exists in the engine (no game-server edit needed):**
- Runtime spawn and despawn with `World.spawn` / `World.remove`. Despawning safely depends on the Phase 5 GS-1 avatar-release fix.
- Barriers as real objects through `World.spawn(DynamicObject)`, the same path the Phase 4 resource nodes use. The client sees them and paths around them.
- Collision can be changed directly: `world.collision` is public, and so are `CollisionFlagMap.add` and `remove`. A flag only the server knows about can disagree with the client's own pathing, so blocking with a real object is preferred. Unverified; needs a 241 client.
- Phased areas through the existing instance system (`InstancedMapAllocator`). Each group gets its own copy of the area, so nothing needs to be hidden per player.
- Caravans need multi-tick movement with route waypoints. Check what the engine's walk queue can do before designing anything.

**What would need engine work (stop and ask):**
- NPCs that only some players can see. That is §2, and it brings the combat, aggression and loot consistency problem with it.
- Hiding other players from a player. That is a larger network-layer change than §2.
- Movement that ignores pathing, such as teleport-chasing.

**Rule conflicts:**
- Event state is runtime state, so it never goes in `data/cfg`. Event definitions can be data that is read at boot.
- Any tick-driven logic needs a cost bound (rule 4).
- Collision changes must always be undone. On event end or server restart nothing may stay changed, so they need a restore list.
- Rule 11 applies to every NPC an event spawns.

**Design principle:** events are opt-in and announced, such as an event mode players join. They are not hidden admin tricks that deceive players or take their items. Opt-in events are also the only kind that can be tested and reasoned about.

**Links:**
- Phase 6 LLM NPCs could later act as event characters.
- The behaviour chains in §1 cover caravan and trait logic.

**Why deferred:** no OSRS basis, and it depends on GS-1 (Phase 5 PR 2) and §1.

**Matrix row:** Idea, "Live World Director (sandbox events)".
