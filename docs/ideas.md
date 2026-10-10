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

**Monster Maker:** these nodes are the optional behaviour layer of a monster profile (section 5.4).

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

**Monster Maker:** this is the visibility and reveal layer of a monster profile (section 5.5) and the last step of its build order.

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

**Goal:** a developer takes any NPC in the game, existing or new, and changes how it looks, what it can do and how it behaves, all as data. The centrepiece is a mash-up cache engine. It builds a new-looking monster by remixing parts already in the OSRS cache, without patching the client, and spawns it live as an opt-in event for friends. `::showonly` (section 2) is one small piece of this, not the feature.

**Why deferred:** it is the autonomous-build stop point. It is not planned or built until the user promotes it.

### 5.1 Foundation: NPC tools on any NPC (rule 11)

Monster Maker sits on top of this layer; it does not replace it.

- **Type level:** override files in `data/cfg/npcs/overrides` plus `::reloadnpcs`, which applies them live (done).
- **Instance level:** `::setwander` and `::setdirection` (done). `::shiftnpc`, `::removespawn`, `::spawnnpc` and `::tileinfo` come in Phase 5 PR 2.
- **Persistence:** edits go to the spawn files through the `data/run/spawn-edits.jsonl` outbox, keyed by stable spawn ids (done).

### 5.2 The mash-up cache engine

The revision 241 protocol already supports these per-NPC changes, which the server sends over the network. They were checked against the rsprot `osrs-241-api` classes `NpcAvatarExtendedInfo`, `NpcAvatarFactory` and `NpcInfo`.

- **Transformation** (`transformation(id)`): the client draws the NPC as another NPC type, with that type's model, skeleton and animations.
- **Body customisation** (`setBodyCustomisation(models, recolours, retextures, recolAll)`): replaces the model parts with other cache models and swaps colours and textures.
- **Head customisation** (`setHeadCustomisation(models, recolours, retextures)`): the same for the chathead.
- **Tinting, name and combat level** (`setTinting`, `setNameChange`, `setCombatLevelChange`): per NPC, for example "Iridescent Demon, level 450".
- **Colour cycling:** resending the recolour every few ticks gives a shimmer. Each resend costs network traffic, so it needs a rate limit set in data (rule 4). Tinting is the cheap alternative.

**What is missing or unverified:**
- **Game-server wrappers:** the game server (`game-server/.../info/NpcInfo.kt`) exposes only tinting, the name change and the combat level change. Transformation and body/head customisation need small game-server wrappers, a gated step under rule 3.
- **Transformation is client-side only.** The server keeps the base NPC's size, collision, combat definition and the animations it sends. A profile's base NPC should therefore be the skeleton donor, for example `npc.general_graardor`, so that the server and the client agree. Transformation is kept for special cases.
- **Undoing a transformation:** rsprot has no `resetTransformation`. TODO: how to revert one; verify on a client.
- **Foreign parts on a borrowed skeleton:** nobody has checked whether they animate correctly; mixed parts may distort. This needs a test with a 241 client first.
- **Model ids, recolour pairs (16-bit HSL) and texture ids:** they come from the cache and are verified, never guessed. Until then they are TODO.

### 5.3 Monster profiles

A designed monster is one data file in `data/cfg/npcs/custom/<profileId>.json`:

- `profileId`, for example `iridescent_demon`.
- `baseNpc`: a real cache NPC by RSCM name. There are no invented cache ids, so nothing collides with the cache (rule 8).
- `appearance`: an optional transformation, body and head models, recolours, retextures, tint, name and combat level.
- `stats`: the same fields as the override files.
- `behaviourChain` (optional, 5.4) and `visibility` (optional, 5.5).
- `provenance`: `custom`, plus author and notes. A profile never claims a wiki source (rule 7).

Reloading a profile updates every live monster built from it, the same way `::reloadnpcs` does: the file is read off the game thread and the result is applied on it. Profiles are data; which monsters are alive is runtime state and never goes in `data/cfg`.

### 5.4 Behaviour chains (optional layer)

These are the nodes of section 1:
- `PERCEPTION`: picks a target, such as the weakest defence or lowest Hitpoints.
- `CRAFTY`: times attacks to the player's weapon recovery.
- `KITER`: steps back from melee range.
- `TIMID`: flees from well-geared attackers.
- `CALL_FOR_HELP`: summons allies.

They run on the game thread with a per-tick cost limit, never on a background thread reading player state (rule 4). Node parameters live in the profile. Ignoring protection prayers is not an OSRS mechanic and needs an explicit decision.

### 5.5 Visibility and spectacle

This is the technique of section 2:
- A monster can start visible only to a target player or a party, through rsprot specific NPCs.
- A trigger, such as dropping to 75% Hitpoints, reveals it to everyone.
- The hard part: combat, aggression and loot must respect visibility. A player who cannot see the monster must not be attacked by it or get its drops.

### 5.6 Dev workflow

1. **Design:** in the Dev Cockpit, pick a base NPC, browse cache models and colours, and set stats and behaviour. It saves a profile. The cockpit imports only `alter-data`, which already reads the cache, so model and colour lists are feasible. A 3D preview in the browser is a separate, larger job.
2. **Preview:** `::spawnmonster <profileId>` spawns it next to you, visible only to you. Tweak, reload and repeat.
3. **Deploy:** spawn it near friends, privately or publicly, as a temporary event or saved in the spawn files.
4. **Live control:** reveal it, despawn it, change its stats or look mid-fight, or reshape any existing NPC the same way.

The cockpit triggers actions only through the server's admin API, with an allowlist and the audit log. It never reaches into the engine.

### 5.7 Guardrails

- Everything is data files plus reload; nothing is hard-coded per monster.
- Fun for friends, not harm: events are opt-in and visible as events. They never take items or trick players into losses.
- Game-server changes stay minimal and tested, and are gated with the user.
- Unverified cache ids and values stay TODO until checked.

### 5.8 Build order (when promoted)

1. A read-only spike report (`data/reports/npc-customisation-241-spike.md`) on the protocol support and real cache model ids for one sample remix.
2. A client test: does a transformation plus foreign body parts look and animate right?
3. Game-server wrappers for transformation and customisation (gated).
4. The profile loader plus `::spawnmonster`: the first playable remix.
5. The cockpit designer UI: a model and colour browser with preview.
6. Behaviour chain nodes (section 1).
7. Visibility and spectacle: `::showonly`, reveal triggers, and the combat and loot consistency fix (section 2).

**Open question (settle at promotion):** are monsters spawnable only by the developer, or also by events such as section 6? That decides how early step 7 is needed. Suggested: developer-only first.

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

**Monster Maker:** monster profiles (section 5) could later be spawned as event characters; whether events may spawn them is section 5's open question.

**Matrix row:** Idea, "Live World Director (sandbox events)".
