# Dev Cockpit

The cockpit is a local control center for Alter2: one page that shows what the system wants to do, lets you say **GO!**, **Edit** or **Delete**, and starts, stops and restarts the game server safely. It is the `dev-cockpit` module: a Ktor server with a Vue UI, SQLite for state, running as its own process next to the game server.

```bash
./gradlew :dev-cockpit:run
```

Then open http://127.0.0.1:43600 and paste the token from `data/cockpit/owner.token` (written on the first run). `--args="--new-owner-token"` issues a fresh one if it is lost. `-PskipWeb` skips the UI build (the API still works); `./gradlew :dev-cockpit:installDist` makes a distribution under `dev-cockpit/build/install/`.

Nothing here touches the game engine: the module depends on `alter-data` only and talks to a running server through its admin API (see [server-control.md](server-control.md)).

## What's on the page

| View | What it does |
|---|---|
| Dashboard | Server state (stopped / starting / running / stopping), health (revision, cycle, players, NPCs, uptime, tick stalled, shutdown countdown), Start / Stop / Restart with a countdown in ticks, Reload wiki snapshot, live tail of `data/logs/alter.log`. |
| Inbox | Cards: what, why (evidence: hit count, first/last seen, places), the exact plan (requests it will make, files it will write) and buttons: **GO!**, **Edit** (the card's parameters as JSON), **Delete** (a reason is required; the automation rules learn from it later), **Snooze 24h**. Filter by status. |
| Audit log | Every decision and control action: when, who (token label and role), what, with which details. |
| Settings | Your role; owners issue and revoke tokens. |

Everything updates live through server-sent events (`/api/events`).

## Where cards come from

- **Missing content.** `data/missing_content.json` (written by the game server; see `::missing`) and the server's live `missing` events become one card per key, kind `enrich.<type>`, e.g. `enrich.npc_op` for "Talk-to on Man (3108) is unscripted". GO resolves the OSRS Wiki page for the id through `Special:Lookup` and runs the enricher for the card's kind (see below). The scaffold generators build on the result.
- **Server exits.** When a server the cockpit started exits with a code other than 0 or 75, a `server.start` card appears with the last log lines as evidence; GO starts it again.

A card's `sourceKey` identifies what it is about, so a source refreshes the evidence of an open card instead of creating duplicates, and a deleted card stays deleted.

Card states: `PENDING` → (`SNOOZED`) → `RUNNING` → `DONE` or `FAILED` (retry with GO), or `DELETED`.

## Scaffolds (work orders, part 2)

After GO, a **Scaffold** button turns the enrichment into files on a branch and shows the diff; **Compile and apply to branch** compiles the card's worktree (`:game-plugins:compileKotlin`, run by the worktree's own Gradle wrapper on the cockpit's IO dispatcher, never on the request thread) and commits only when it passes, otherwise the card shows the compiler's `e:` lines and the files stay staged for a retry; **Discard** removes the branch and worktree. JSON-only scaffolds skip the compile (the server validates them at boot), except a consumables scaffold: its file names a verify task, so the gate runs `:game-plugins:test --tests *ConsumablesDataTests` in the worktree instead of `compileKotlin`. `workorders.compileGate: false`, `compileTask` and `compileTimeoutSeconds` (900) in `data/cfg/cockpit.yml` tune the gate. Nothing touches `main` and nothing is pushed: each card gets a git worktree under `data/cockpit/worktrees/<card>` on a `cockpit/<kind>-<name>-<id>` branch cut from `workorders.baseRef` (`main`).

| Enrichment | Scaffold | Notes a human must act on |
|---|---|---|
| talk-to | `<Name>Plugin.kt` in `areas/<location>/npcs` when that package exists (else `content/generated`): `onNpcOption(..., "talk-to")` and a `dialog()` mirroring the standard section (chatNpc/chatPlayer, option menus, random greetings, conditions as TODOs) | quest-state sections are listed, not generated; shops opened need `createShop` |
| trade | a shop plugin: `createShop` with the wiki stock as `ShopItem(getRSCM(...), stock, sell, buy)` and `onNpcOption(..., "trade")` | items missing from `item.rscm`, non-coin currencies, restock times |
| pickpocket | an entry appended to `data/cfg/thieving/pickpockets.json` (loot from the thieving lines, weight = wiki rarity in percent) | thieving xp, stun and success rates |
| scenery Open/Close | a `single-doors.json` entry; not applyable until the other state's id is filled in | find the opened/closed object id |
| scenery Climb | `onObjOption(..., "climb-up")` stub | the destination tile |
| recipe | `onItemOnObj`/`onItemOnItem` stub with level check, material removal, output and xp | animation, timing, failure |
| consumable (INV_OP on an item whose infobox offers Eat or Drink) | entries appended to the `consumables` array of `data/cfg/consumables/consumables.json`: one per dose down to `item.vial` for a potion, one for a food; `heal: {fixed}` only from a lead sentence "heals/restores N hitpoints", `antipoison`/`antivenom` from "cures ..." and "immunity to ... for N minutes/seconds"; not applyable without an RSCM name or any heal or effect | combo food, fast-food delays, lead sentences that were not parsed, immunity ranges and page conflicts |
| anything else | a plugin skeleton with the matching hook | everything |

Generated code says where it came from (wiki URLs, CC BY-NC-SA 3.0) and is a reviewed starting point, never merged blindly.

## Server control

The supervisor finds the game server through `data/run/server.json` and its admin API, so it also sees a server started by `scripts/alter`, IntelliJ or Docker. Start launches the installed distribution (`./gradlew :game-server:installDist` first) from `game-server/` with `supervisor.javaOpts`, console output to `data/logs/console.log`; a managed server that exits with code 75 (`::update`, Restart) is started again. Stop and Restart go through the admin API, so players are logged out and saved; if the API is unreachable and the process is the cockpit's, it is destroyed after `stopTimeoutSeconds`.

## Tokens and roles

| Role | May |
|---|---|
| viewer | read everything: dashboard, inbox, audit, log |
| dev | also decide cards and control the server |
| owner | also issue and revoke tokens |

Tokens are random, stored hashed in SQLite, and sent as `Authorization: Bearer <token>`. The event stream accepts `?token=` because `EventSource` cannot send headers. The cockpit binds to `127.0.0.1` by default; change `bindAddress` in `data/cfg/cockpit.yml` only if you accept that the token is the only lock.

## API

All under `/api`; JSON in and out; every route except `/api/health` needs a token.

| Route | Role | Effect |
|---|---|---|
| `GET /health` | none | cockpit status, revision, schema version, inbox counts |
| `GET /me` | viewer | the caller's token id, role and label |
| `GET /server`, `GET /server/log?lines=` | viewer | server status and health; last log lines |
| `POST /server/start`, `/server/stop?ticks=`, `/server/restart?ticks=`, `/server/wiki-reload` | dev | control |
| `GET /inbox?status=`, `GET /inbox/counts`, `GET /inbox/{id}` | viewer | cards |
| `POST /inbox/{id}/go` `{params?}`, `/edit` `{params}`, `/delete` `{reason}`, `/snooze` `{hours|until}` | dev | decisions |
| `POST /inbox/{id}/scaffold`, `/apply`, `/discard` | dev | generate and preview files on a branch; compile then commit them (returns at once with `result.compile.state = compiling`, the verdict arrives as `inbox.updated`); throw them away |
| `GET /audit?limit=&before=` | viewer | audit entries, newest first |
| `GET /missing?limit=` | viewer | the raw missing-content file |
| `GET /tokens`, `POST /tokens` `{role,label}`, `DELETE /tokens/{id}` | owner | token management |
| `GET /events` | viewer | SSE: `inbox.created`, `inbox.updated`, `server.lifecycle`, `server.missing`, `log` |

## Files

| Path | What |
|---|---|
| `data/cfg/cockpit.yml` | bind address, port, supervisor, inbox and work-order (compile gate) settings (all optional) |
| `data/cockpit/cockpit.db` | SQLite: tokens, inbox cards, audit log (schema migrations run on start) |
| `data/cockpit/owner.token` | the bootstrap owner token, plaintext |
| `dev-cockpit/web/` | the Vue 3 + Vite UI; Gradle builds it into the jar (`static/`) with a Node it downloads |

For UI work, run the cockpit once and then `npm run dev` in `dev-cockpit/web/`: Vite serves the UI with hot reload and proxies `/api` to the running cockpit.
