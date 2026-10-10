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

**Implementation note:** use rsprot's built-in specific NPCs, `NpcAvatarFactory.alloc(specific = true)` plus `npcInfo.setSpecific` and `npcInfo.clearSpecific`. Do not build a custom encoder bitmask. The approved Phase 5 plan carries this as PR 3 (`::showonly` / `::showall`), a manual gate because its game-server wrapper cannot be unit-tested.

**Why deferred:** its content use belongs to Monster Maker, and the game-server wrapper it needs has no regression test (rule 3).

**Rule conflicts to resolve first:**
- `net.rsprot` stays in the network layer (`.claude/rules/game-server.md`), so content needs a game-api hook rather than direct rsprot calls.
- Visibility sets are runtime state, never in `data/cfg`.
- Combat, aggression and drops must agree with visibility: a player who cannot see an NPC must not be attacked by it or loot it. Check how far that reaches into the engine before planning.

**Matrix row:** Idea, "Selective visibility (spectacle)". Phase 5 PR 3 (`::showonly`) is the only roadmap item that builds it, and it waits on a manual gate.

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
