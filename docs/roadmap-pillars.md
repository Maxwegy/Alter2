# Roadmap pillars: design notes

Design for the gameplay pillars the Phase 1 plan never designed. Nothing here is built; each section says where
the data comes from, which files it lands in, whether the engine needs a seam (confirmed none unless stated), how
it is tested, and what is still open and how to answer it. Written 2026-10-09 against `main` at #8.

Ground rules that apply to every pillar:

- Wiki data reaches the repository only through `wikiSync` into `data/cfg/wiki/`, which is never hand-edited.
  Hand-maintained config under `data/cfg/<pillar>/` may cite wiki facts, and every citation is an
  `https://oldschool.runescape.wiki/...` URL (never the RS3 wiki).
- Numbers that nobody has verified stay **blank with a `TODO`**; nothing is invented. The Dev Cockpit's enrichers
  (`dev-cockpit/.../workorders/Enrichers.kt`) are what fetches missing numbers later, each one named below, not a
  human copying from a page.
- Content refers to things by RSCM name (`object.tree_1276`, `item.shark`), never by id.
- No engine edits unless a seam is proven missing; content uses `DynamicObject`, `TimerKey`, `World.spawn`,
  `World.canTraverse` and the plugin DSL as they are.
- An external AI proposal for `resources.json` / `consumables.json` was used for shape only. Its numbers were
  not trusted (several were stale or RS3), its JSON was invalid (blank values), and it cited the RS3 wiki.
  Every number below was checked against the OSRS wiki in this session or left blank.

---

## 1. NPC spawns

Only NPCs are missing: objects come from the cache maps. Today spawns are hand-written per area
(`game-plugins/.../content/areas/lumbridge/spawns/SpawnPlugin.kt`, `spawnNpc(npc = "npc.man_3106", x, z,
walkRadius, height, direction)`).

**Data source.** The `{{Map|...}}` template in each NPC page's wikitext, e.g. Hans:

```
|map = {{Map|name=Hans|3212,3219|rectX=23|rectY=31|mtype=rectangle}}
|id = 3105
```

Multi-version pages pair `mapN` with `idN` (and `versionN`), which `Infobox.forVersion` already resolves.
`InfoboxParser` currently **strips** `{{Map|...}}` before cleaning values
(`dev-cockpit/.../workorders/Infobox.kt`, the `replace(Regex("""\{\{Map\|[^}]*}}"""), "")` in `template()`),
so the template is lost today; it must be parsed into a structured `maps: List<MapTemplate>` (per numbered
suffix) before `TranscriptParser.clean` runs on the rest of the value.

Template semantics, from [Template:Map/doc](https://oldschool.runescape.wiki/w/Template:Map/doc):

- `mtype` is one of `pin` (default), `rectangle`, `square`, `circle`, `dot`, `text`, `line`, `polygon`, ...;
  `rect` is not a value.
- Pins "mark things that take up a 1x1 space or otherwise have an exact X/Y location".
- A rectangle is "defined by a single point" with side lengths `rectX`/`rectY` (defaults 20, minimum 1).
  Circles and squares take "a centre point" plus `r`. The doc leaves the rectangle's anchor open, but the
  template's Lua (https://oldschool.runescape.wiki/w/Module:Map, `feat.rectangle`) settles it: the coordinate is
  the **centre**, `rectX`/`rectY` are **full side lengths** in tiles, halved with `math.floor`
  (`xLeft = x - floor(rectX/2)`, `xRight = x + floor(rectX/2)`, plus one extra tile on the east/north side when
  the length is odd). Hans's `3212,3219 rectX=23 rectY=31` is therefore x 3201–3224, y 3204–3235.
- `plane` is 0–3, default 0.
- Several pins in one template are several coordinates; several Maps are several locations.

Coverage, counted with the wiki search API (`hastemplate:` queries, 2026-10-09):

| infobox | pages | pages with a `{{Map` | share |
|---|---|---|---|
| `Infobox NPC` | 4,541 | 3,191 | 70% |
| `Infobox Monster` | 1,633 | 79 | 5% |

So the template covers most non-combat NPCs and almost no monsters. Monsters stay hand-placed; there is no
external dataset to fall back on (osrsbox is dead, 2004scape is 2004-era data with different ids).

**Files.**

- `data/cfg/spawns/npc_spawns.json` (new; hand-maintained and cockpit-written, next to the existing
  `item_spawns.yml`). One entry per spawn: `npc` (RSCM), `x`, `z`, `height`, `walkRadius`, `direction`,
  `source` (wiki URL or `manual`). Entries written by the cockpit carry the page URL.
- A spawn plugin in `game-plugins` that reads the file at `onWorldInit` and calls `spawnNpc`. Existing per-area
  `SpawnPlugin.kt` files move into the JSON over time; both can coexist (ids are not unique keys, tiles are).
- Cockpit: a `MapTemplate` parser in `Infobox.kt`, an `enrich.spawn` enricher (page → map templates → candidate
  tiles) and a `spawn` scaffold kind that appends to `npc_spawns.json` (the existing `JSON_APPEND` mode).

**Engine seams.** None. `World.spawn(Npc)`, `KotlinPlugin.spawnNpc`, `World.canTraverse(tile, direction,
pawn)` and the collision map are public today.

**Tests.**

- `InfoboxParserTests`: Hans fixture → one `MapTemplate(name=Hans, x=3212, y=3219, rectX=23, rectY=31,
  mtype=rectangle, plane=0)`; a multi-version page keeps `map1`/`map2` paired with `id1`/`id2`; a page without
  a Map yields none; the rest of the infobox values are unchanged by the change.
- Spawn validation without a client (cache-dependent, `Assume`d): the spawn tile is walkable in the collision
  map, and at least one neighbour within `walkRadius` is reachable via `World.canTraverse`. Run over every entry
  of `npc_spawns.json` so a bad coordinate fails CI on machines with a cache.
- Plugin test: the JSON entry for Hans produces one `Npc` at the tile with the given walk radius.

**Open questions.**

1. *Rectangle anchor:* answered above from `Module:Map` (centre, full side lengths). A fixture test on Hans
   locks the bounds (x 3201–3224, y 3204–3235) once the parser exists.
2. *Which tile of a rectangle becomes the spawn?* Proposal: the rectangle's centre if walkable, else the nearest
   walkable tile inside it; `walkRadius` = half the smaller side. Pins spawn exactly there with `walkRadius` 0.
3. *How many NPC pages give several Maps for several ids?* Count during the first enricher run and report it.
4. *Plane.* `plane=` maps to `height`; verify with a `plane=1` page (e.g. an upstairs shopkeeper).

---

## 2. Resource nodes (Phase 4 prerequisite)

**Data source.** Hand-maintained `data/cfg/resources/resources.json` (never under `data/cfg/wiki/`), one entry
per node kind. Verified facts so far, each with its source:

| fact | value | source |
|---|---|---|
| Regular tree: level, xp | 1, 25 | https://oldschool.runescape.wiki/w/Tree |
| Regular tree: depletes after one log | always | same ("always felled after one log") |
| Regular tree: respawn | 36–60 s, random (60–100 ticks) | same |
| Regular tree: nests | clue nests only, no bird nests | same |
| Oak: level, xp | 15, 37.5 | https://oldschool.runescape.wiki/w/Oak_tree |
| Oak: depletion | **timer**, 45 ticks of chopping; the old 1/8-per-log rule is gone | same |
| Oak: respawn | 14 ticks (8.4 s) | same |
| Oak: bird nest | 1/256 instead of a log (1/230 with the cape) | same |
| Woodcutting roll interval | every 4 ticks | https://oldschool.runescape.wiki/w/Woodcutting |
| Coal: level, xp | 30, 50 | https://oldschool.runescape.wiki/w/Coal_rocks |
| Coal: depletion, respawn | every success; 30 s (50 ticks) | same |
| Coal: success per roll | 17/256 at level 1 → 101/256 at 99, pickaxe-independent | same |
| Mining roll interval per pickaxe | bronze 8, iron 7, steel 6, black 5, mithril 5, adamant 4, rune 3, dragon 3 (1/6 chance of 2) ticks | https://oldschool.runescape.wiki/w/Mining |
| Mining gem roll | 1/256 before each ore roll; a gem replaces the ore | same |
| Success formula | P(L) = (1 + ⌊low·(99−L)/98 + high·(L−1)/98 + 0.5⌋)/256, clamped | https://oldschool.runescape.wiki/w/Skilling_success_rate |

Two corrections to the proposal: oak depletion is a timer, not `[1, 8]`, and regular trees never drop bird
nests. Woodcutting and Mining differ in shape: woodcutting success depends on **axe tier and level** (one
low/high pair per axe); mining success depends on **level only** and the pickaxe sets the **roll interval**.

Woodcutting low/high per axe (out of 256, from the "Cut chance" charts):

| axe | tree low → high (level reaching 256) | oak low → high (level reaching 256) |
|---|---|---|
| bronze | 65 → 201 (99) | 33 → 101 (99) |
| iron | 97 → 256 (78) | 49 → 151 (99) |
| steel | 129 → 256 (47) | 65 → 201 (99) |
| black | 145 → 256 (37) | 73 → 226 (99) |
| mithril | 161 → 256 (29) | 81 → 251 (99) |
| adamant | 193 → 256 (17) | 97 → 256 (78) |
| rune | 225 → 256 (8) | 113 → 256 (60) |
| dragon | 241 → 256 (4) | 121 → 256 (53) |
| crystal | 251 → 256 (2) | 126 → 256 (48) |

Tree values are quoted from the page as fractions of 256; the oak page gives decimals (e.g. bronze
0.12890625 → 0.39453125), converted here by ×256. Sources: https://oldschool.runescape.wiki/w/Tree,
https://oldschool.runescape.wiki/w/Oak_tree. The "high" column is where each curve reaches 100%, not a
level-99 value, so storing the pair as `low` at level 1 and `high` at level 99 needs the page's own high value;
the enricher should read the chart data rather than these derived rows.

**Shape** (every number carries `source`; unknown values are `null` with a `todo`):

```json
{
  "id": "oak",
  "skill": "woodcutting",
  "level": 15,
  "experience": 37.5,
  "nodes": ["object.oak_tree_4533", "object.oak_tree_4540", "object.oak_tree_8462", "object.oak_tree_8463",
            "object.oak_tree_8464", "object.oak_tree_8465", "object.oak_tree_8466", "object.oak_tree_8467",
            "object.oak_tree_9734", "object.oak_tree_10820"],
  "depleted": "object.tree_stump_1356",
  "roll_interval_ticks": 4,
  "depletion": {"timer_ticks": 45},
  "respawn_ticks": 14,
  "success": {"by_tool_tier": {"item.bronze_axe": {"low": 33, "high": null}, "item.iron_axe": {"low": 49, "high": null}}},
  "rewards": [{"item": "item.oak_logs", "amount": 1}],
  "tertiary_rewards": [{"item": "item.bird_nest_5071", "chance": [1, 256], "replaces_reward": true}],
  "sources": {"level": "https://oldschool.runescape.wiki/w/Oak_tree", "success": "https://oldschool.runescape.wiki/w/Oak_tree"},
  "todo": ["success.high per axe: read from the page's chart data (enrich.resource)"]
}
```

`depletion` is one of `{"always": true}` (regular tree, coal), `{"chance": [n, d]}` (juniper 1/16, blisterwood
1/10 per the Woodcutting page) or `{"timer_ticks": n}` (oak). Mining entries use
`"success": {"by_level": {"low": 17, "high": 101}}` and `"roll_interval_ticks": {"by_tool_tier": {...}}`.
Every RSCM name above exists in `data/cfg/rscm/object.rscm` / `item.rscm` (checked: tree 1276/1277/1278 →
stump 1342; the ten oak ids → stump 1356; `coal_rocks_11366`/`11367` → `rocks_11390`; the axes, pickaxes, logs,
gems and `bird_nest_5071`–`5075`). Which bird-nest id is the generic one is a `TODO` for the enricher.

**Files.** `data/cfg/resources/resources.json`; a `ResourceNodePlugin` in `game-plugins/.../content/skills/`
binding `onObjectOption` for every `nodes` entry, with the gathering loop as a player queue task; node state
(depleted, timer) on the `DynamicObject` replacement plus a world timer to restore it.

**Engine seams.** None. `World.spawn/remove(GameObject)`, `DynamicObject(other, id)`, `TimerKey`, player
queues and `player.getSkills()` cover it.

**Tests** (pure, with a fixed `Random`): reject below `level`; a success replaces the node with `depleted` and
gives the reward and xp; `{"chance": [n, d]}` depletion is rolled per reward; `{"timer_ticks"}` depletion fires
on the next success after the timer; the tool tier changes the success pair (woodcutting) or the roll interval
(mining); the restore timer puts the original object back; a tertiary roll replaces the main reward when
`replaces_reward` is true. Plus a schema test that every `nodes`/`depleted`/`item` name resolves in RSCM and
every numeric field has a `source` or a `todo`.

**Open questions.**

1. *Oak "high" values:* the chart reaches 1.0 before 99 for adamant+; store what the page's chart data gives at
   level 99 (the enricher reads the `{{Skilling success chart}}`/data template, not the rendered graph).
2. *Regular-tree respawn is a range (36–60 s):* model as `respawn_ticks: {"min": 60, "max": 100}`.
3. *Mining Guild halves respawn; gloves save depletion:* out of scope for the template; note in `todo`.
4. *Generic bird nest id and the ring/seed/egg split:* read https://oldschool.runescape.wiki/w/Bird_nest.
5. Enricher: `enrich.resource` takes a `LOC_OP Chop down/Mine` missing-content key, resolves the object page
   via `Special:Lookup`, reads the infobox (level, xp, respawn), the chart data and the depletion sentence, and
   proposes a `resources.json` entry with every number sourced; unknowns stay `null` + `todo`.

---

## 3. Consumables (Phase 3 prerequisite)

**Data source.** `data/cfg/consumables/consumables.json`, hand-maintained, replacing the `Food` enum and
`EatingPlugin` (`game-plugins/.../content/items/consumables/food/{EatingPlugin,Food,Foods}.kt`).

Verified mechanics (the Tick manipulation page only covers the skilling timer, so the rules come from the Food
pages):

| fact | value | source |
|---|---|---|
| Standard food eat delay | 3 ticks before the next food | https://oldschool.runescape.wiki/w/Food/Fast_foods |
| Standard food attack delay | +3 ticks, and "only add to an existing attack delay" | same |
| Potions | "respect a different delay timer"; no 3-tick attack or eat delay | same |
| Combo foods (karambwan, halibut, gnome foods) | ignore the eat delay of other foods; 2-tick attack delay | same, https://oldschool.runescape.wiki/w/Food |
| Attack delay stacks | shark + karambwan = 3 + 2 = 5 ticks | https://oldschool.runescape.wiki/w/Food/Fast_foods |
| Three-item combo | food, then brew, then combo food in one tick heals all three | same (marlin, Saradomin brew, halibut example) |
| Shark heal | 20 | https://oldschool.runescape.wiki/w/Food |
| Cooked karambwan heal | 18 | same |
| Saradomin brew heal | ⌊base HP × 15/100⌋ + 2 | https://oldschool.runescape.wiki/w/Saradomin_brew |
| Saradomin brew Defence | ⌊base Defence × 20/100⌋ + 2 (the page's formula line renders "15"; its table is 20%) | same |
| Saradomin brew drain | Attack/Strength/Ranged/Magic: ⌊current × 10/100⌋ + 2 (level 1 → −1) | same |

So OSRS has three independent timers: **food**, **potion**, **combo food**. Each item names which timer it
takes and how many attack-delay ticks it adds.

**Shape:**

```json
[
  {"item": "item.shark", "timer": "food", "heal": {"fixed": 20}, "attack_delay_ticks": 3, "delay_ticks": 3,
   "source": "https://oldschool.runescape.wiki/w/Shark"},
  {"item": "item.cooked_karambwan", "timer": "combo", "heal": {"fixed": 18}, "attack_delay_ticks": 2, "delay_ticks": 3,
   "source": "https://oldschool.runescape.wiki/w/Cooked_karambwan"},
  {"item": "item.saradomin_brew4", "timer": "potion", "heal": {"percentOfMax": 15, "plus": 2}, "attack_delay_ticks": 0,
   "delay_ticks": null, "replacement": "item.saradomin_brew3",
   "skill_effects": [{"skill": "defence", "percent": 20, "plus": 2}, {"skill": "attack", "percent": -10, "plus": 2, "of": "current"},
                     {"skill": "strength", "percent": -10, "plus": 2, "of": "current"}, {"skill": "ranged", "percent": -10, "plus": 2, "of": "current"},
                     {"skill": "magic", "percent": -10, "plus": 2, "of": "current"}],
   "source": "https://oldschool.runescape.wiki/w/Saradomin_brew",
   "todo": ["potion delay_ticks: the Fast foods page says potions use a separate timer but gives no tick count"]}
]
```

`heal` is `{fixed}` or `{percentOfMax, plus}` (of base Hitpoints, floored). Overheal foods (anglerfish) add
`"overheal": true`. All names exist in `item.rscm` (`shark`, `cooked_karambwan`, `saradomin_brew4..1`).

**Files.** `data/cfg/consumables/consumables.json`; `ConsumablesPlugin` replacing `EatingPlugin`; three
`TimerKey`s on the player (`FOOD_DELAY`, `POTION_DELAY`, `COMBO_FOOD_DELAY`) plus the existing attack timer.

**Engine seams.** None: `player.timers`, `player.getSkills().alterCurrentLevel`, the attack-delay timer and
`onItemOption("Eat"/"Drink")` exist. (Follow-up item 3, string-bound item options possibly never firing, must be
verified first or the plugin binds raw ops.)

**Tests** (pure, world without cache): a second standard food on the same tick is blocked; food adds the attack
delay only when an attack delay is pending, and shark + karambwan stacks to 5; brew heal is computed from
max HP (99 → 16); brew lowers Attack by ⌊current/10⌋ + 2; shark → brew → karambwan in one tick passes all
three timers and heals 20 + brew + 18.

**Open questions.**

1. *Potion delay in ticks:* not on the pages fetched; answer by reading https://oldschool.runescape.wiki/w/Potion
   and the Fast foods page's "1-tick eat delay" notes (enricher `enrich.consumable`, review-required).
2. *Does eating reset or extend the attack timer when none is pending?* The page says it only adds to an
   existing delay; confirm against the Combat page before coding.
3. *Hunter meats' secondary heal and anglerfish's level-scaled heal:* out of scope for the first file; model as
   `heal.byLevel` later.
4. Enricher: `enrich.consumable` reads the item infobox (`heal`, "Eat delay", "Attack delay" fields where present,
   as the Food page's table has them for some items) and proposes an entry; review-required because the table is
   incomplete.

---

## 4. Special attacks and autocast (Phase 3)

**What exists.** `content/combat/specialattack/` with `SpecialAttacks.register(item, energy) { ... }` and four
weapons (abyssal bludgeon, abyssal dagger, armadyl godsword, dragon dagger). Each plugin hard-codes its
animation id (`player.animate(id = 1062)` for the dragon dagger), accuracy multiplier and hit logic. Autocast
has no memory: the chosen spell is not stored.

**Data source.** Wiki prose under `==Special attack==`, e.g. the dragon dagger's Puncture "deals two hits at
once", consuming "25% of the wielder's special attack energy", each hit with "an extra 15% accuracy and 15%
damage" (https://oldschool.runescape.wiki/w/Dragon_dagger). It is parseable by pattern (`(\d+)% of ...
special attack energy`, `(\d+)% accuracy`, `(\d+)% damage`, `two hits`), but prose varies, so every parse is
review-required. Animation and graphic ids are not on the wiki: they stay in a hand-kept registry.

**Files.** `data/cfg/combat/special_attacks.json`: `item`, `energy` (percent), `accuracy_multiplier`,
`damage_multiplier`, `hits`, `animation`, `graphic` (hand-kept, `source: "manual"`), `source` URL for the
numbers; the existing plugins keep custom logic (e.g. AGS's healing) and read the numbers from the file.
Autocast: a player attribute + save field (`AUTOCAST_SPELL`) set from the spellbook/attack-tab varbit, read by
`MagicCombatStrategy`; a `data/cfg/combat/autocast.json` listing which staves allow which spell groups.

**Engine seams.** None for specials (all in `game-plugins`). Autocast persistence uses the existing
`PlayerSaving` attribute map; verify that the attack tab's autocast varbit is already sent/handled by the
`IfButton` handlers, otherwise a `game-plugins` binding, not an engine edit.

**Tests.** The registry entry for the dragon dagger yields two hits with the 1.15 multipliers and drains 25%;
energy below the cost refuses; the parser turns the Puncture sentence into `{energy: 25, accuracy: 1.15,
damage: 1.15, hits: 2}` and flags a sentence it cannot parse; autocast survives logout/login (save round trip).

**Open questions.**

1. *How uniform is the prose?* Sample 20 `==Special attack==` sections with the enricher (`enrich.special`) and
   count parse failures before trusting it.
2. *Where do animation/graphic ids come from?* Hand-kept registry; the cache's sequence names on 241 (index 24
   gameval tables, see `docs/phase-1.5-cache-241.md`) may name them, which would make them RSCM-addressable.
3. *Autocast UI:* which interface/varbit the 228/241 client uses for the autocast selection; read the attack tab
   enum/struct in the cache (`::missing` will show the unhandled `IF_BUTTON`).

---

## 5. Social (Phase 2)

**What exists.** Friends and ignores: `game-server/.../model/social/Social.kt` (push/add/delete, private
messages, status) and `game-plugins/.../tabs/friends_list/{FriendsListPlugin,IgnoresListPlugin,Social}.kt`.
Missing: clan chat and the privacy filters (public/private/trade chat modes).

**Data source.** None from the wiki beyond UI strings; this pillar is pure mechanics. Clan chat needs:
channel owner, ranks, join/leave/kick, the "talk in clan" `/` prefix, and the rank-based permission matrix
from https://oldschool.runescape.wiki/w/Clan_Chat (verify the rank names there).

**Files.** `game-server/.../model/social/ClanChat.kt` (channel model, owner-keyed) is engine-side only if the
message router needs it; prefer `game-plugins/.../content/social/clan/` with the channel registry as a
`Service`. Privacy: a `PrivacyFilter` on `Player` (`public`, `private`, `trade` modes) read by the chat
handlers; persisted in the save (already has `friends`/`ignores`).

**Engine seams.** Likely one: the public chat broadcast and private-message routing live in game-server
(`Social.sendPrivateMessage`, the chat message handler). If the filter cannot be applied from a plugin
(the handler sends directly), that is one small game-server edit with a test. Confirm by reading the chat
handler before the phase starts.

**Tests.** Ignored player's message is not delivered; `private: friends` delivers only from friends; clan join,
kick by rank, and message fan-out to members only; save round trip of the privacy modes.

**Open questions.** Clan ranks and permissions (read the wiki page); whether the 228 client's clan tab uses the
legacy clan chat or the newer Clans system (check the interface ids in the cache); nothing an enricher can fetch.

---

## 6. Database

Not a phase; a follow-up list item. Stay on atomic JSON saves plus autosave (`PlayerSaving`, `PlayerAutosave`,
`AtomicFiles`). Mongo stays a stub (`game-server/.../saving/formats/impl/Mongo.kt` is
`TODO("Not yet implemented")`, the driver is already on the classpath) and is picked up only when a
cross-server lookup (shared bans, highscores, multi-world accounts) needs it. No tests or data until then.

---

## Sequencing

Spawns first (unblocks every area), then consumables and specials (Phase 3), then resource nodes (Phase 4),
then social (Phase 2 scope but independent). Each starts with its enricher in the cockpit, so the first real
numbers enter the config files through a reviewed card, not by hand.
