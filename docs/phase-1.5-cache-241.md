# Phase 1.5 spike: reading the revision 241 cache

Spike on the Phase 1.5 blocker (2026-10-09): the revision 241 cache (OpenRS2 cache 2735, build 241, 2026-10-07)
could not be read. The vendored filestore threw `BufferUnderflowException` in `CacheManager.init`, and displee
7.1.0 (used by the staging verifier) failed on indices 2 and 19. Revision 228 (OpenRS2 2043) read cleanly.

## Root cause

Every index's reference table in the 241 cache sets **flag `0x4`** ("lengths": the compressed and uncompressed
length of each group, 8 bytes per group). Both readers only knew flags `0x1` (name hashes) and `0x2` (whirlpool
digests), so after the CRC block they read the file counts out of the lengths block and ran off the end of the
table.

Reference-table block order after the group ids, as the client reads it:

| block | size per group | present when |
|---|---|---|
| name hashes | 4 | flag `0x1` |
| CRCs | 4 | always |
| uncompressed CRCs | 4 | flag `0x8` |
| whirlpool digests | 64 | flag `0x2` |
| compressed + uncompressed lengths | 8 | flag `0x4` |
| versions | 4 | always |
| file counts, then file ids | smart | always |

Observed flags per index (python probe over `idx255`, both staged caches):

| cache | flags seen | indices |
|---|---|---|
| 228-2043 | `0x0`, `0x1` | 0–21 (16 empty); 22 entries in idx255 |
| 241-2735 | `0x4`, `0x5` | 0–24 (16 and 23 empty); 25 entries in idx255; 22 and 24 are new |

With the vendored parser's logic, 18 of the 23 non-empty 241 tables throw at the end of the buffer and the other
five "parse" with hundreds of KB left over. Parsed with the lengths block honoured, every 241 table ends with
0 bytes remaining except those with name hashes (`0x5`), which leave the trailing file-name-hash block unread,
exactly like 228's `0x1` tables.

Displee's own history dates the change: *"Fix ReferenceTable reading for osrs 229 due to lengths being added"*
(2025-02-20, released in 7.2.0). So the flag arrived with revision 229, the first revision after ours, which is
why 228 was the last cache we could read.

Two other 241 facts from the probe, not causes of the failure:

- Index revisions are now Unix timestamps (e.g. `1791285254`), not small counters. They still fit an int.
- Index 24 holds the **gameval name tables** as plain text (group 0 starts `mcannonremains mcannontoolkit
  mcannonball ...`, group 1 `farming_tools_leprechaun molanisk slayer_abberant_spectre_1 ...`). Index 22 is binary.
  `./gradlew :plugins:tools:gamevalDump -PcacheArgs="<cache dir>"` reads them (one group per kind, one file per
  id) into `data/reports/gameval/<kind>.txt` and compares the item/npc/loc tables with `data/cfg/rscm`: on
  241 every committed RSCM id has a gameval name, and the names differ in style (`dwarf_remains` vs
  `mcannonremains`), so generating RSCM from them is a naming decision, not a data gap.

## What displee does differently

Displee 7.1.0 reads the `0x4`/`0x8` blocks only on its RS3 path (`CacheLibrary.isRS3()`); its OSRS path goes
name hashes, whirlpool, CRCs, versions and ignores both flags, so it misparses the same way. The failures
surfaced only on indices 2 and 19 (`ArrayIndexOutOfBounds`, `NegativeArraySize`) because elsewhere the garbage
file counts happened to stay in range; the verifier saw 3 groups in the whole cache. Displee master and the
7.2.0+ releases read the blocks in the client's order for every cache.

## What was fixed in this spike

1. **Vendored filestore** (`plugins/filestore/.../ReadOnlyCache.kt`): the reference-table parsing moved into
   `readReferenceTable(indexId, bytes)`, which now skips the `0x8` and `0x4` blocks in the client's order. For
   228's `0x0`/`0x1` tables the bytes skipped are identical to before (CRCs + versions = 8 per group).
   `ReferenceTableTests` builds synthetic tables for flags `0x0` to `0xF` and, when `data/cache-staging/241-2735`
   exists, loads the real 241 cache and checks index 2 (41 groups, 200,304 files) and index 19 (54 groups,
   207 files).
2. **Staging verifier**: `plugins/tools` moved from displee 7.1.0 to 7.3.0. `cacheStage --verify` on 241 now
   reports 117,665 groups, 0 missing, 0 CRC mismatches.

After these, `cacheDryRun` on 241 gets past `Cache.load` and fails in the **NPC decoder** instead: 241 definitions
use config opcodes added between 229 and 241 that our decoders don't know, and an unknown opcode desyncs the
stream (21,620 "Unable to decode" warnings, then `BufferUnderflowException` in `NPCDecoder.read`). That is the
next layer, not this blocker, and it is Phase 1.5 work.

## Decoder opcodes 229–241 (done in the follow-up PR)

The opcode catch-up landed right after the spike: every definition kind of the 241 cache now decodes with
0 failures and 0 unknown opcodes (`cacheDryRun`: 34,646 items, 16,631 NPCs, 62,534 locs, matching the gameval
tables one for one), verified by `Revision241DecodeTests` against the staged cache. What was added, with
RuneLite's loaders as the second reference where upstream OpenRune was behind:

- NPC: 42, 61, 62, 126, 129, 130, 145–152, 251, 252, 253; 111 means render priority from revision 233 on.
- Item: 9, 15, 44–54, 99, 160, 161, 200–202, 251.
- Loc: 6, 7, 42, 91, 93, 94, 95, 96, 100–102.
- Sequence: 16 is a vertical-offset byte from 226 on, plus 18 and 19. Enum: 7 and 8 (long values).
- `ScriptVarType.DBTABLE` (118), which DB table 154 references.

The list below is the delta as measured during the spike, kept for the record. Compared with upstream OpenRune's
current OSRS codecs (`definition/osrs/.../codec/*.kt`, which read 241):

| decoder | opcodes we lack |
|---|---|
| NPC | 42, 61, 62, 126, 130, 145, 146, 147, 148, 149, 150, 151, 152, 252 |
| Item | 15, 44–54, 99, 160, 161, 200, 201, 202, 251 |
| Object | 6, 7, 91, 93, 95, 96, 100, 101, 102 |

Upstream's "241" commit (`46e2be19`, 2026-10-01) added only `recolAll` (NPC/object/spotanim 42; item 99),
item 161 (`keepOnlyDuringSeqs`) and item 251 (`unlockable`); the rest arrived in earlier revisions. Enum, struct,
varbit, sequence and DB decoders were not reached, so their deltas are unknown until the three above are done.

## Fix options

| option | what | effort | notes |
|---|---|---|---|
| **A. Patch the vendored filestore** (done for the reference table) | Add the missing opcodes to `NPCDecoder`, `ItemDecoder`, `ObjectDecoder` (and fields on the `*Type` classes), copying payload shapes from upstream's codecs; re-run `cacheDryRun` until `unknownOpcodeWarnings` is 0, then do the same for whatever the enum/struct/seq/DB decoders report | about a day | Keeps our API (`CacheManager`, `ItemType`/`NpcType` used by game-server, game-plugins and alter-data). Each opcode is a few lines; the dry-run is the test. |
| B. Bump to upstream OpenRune-FileStore | Upstream no longer ships `dev.openrune.cache.filestore`; it is now a displee-based `filesystem` + `definition` split (`dev.or2`, Kotlin 1.9 / Java 11 target) with different type and decoder classes | days, touches every cache consumer | Also pulls a Kotlin 1.9 artifact into our Kotlin 2.0.20 build. Not a drop-in. |
| C. Swap readers behind `CacheView` | Put displee behind alter-data's `CacheView` and have the server read through it | large | Doesn't remove the decoder work (displee has no definition decoders). |

## Recommendation

**Option A.** The reference-table fix and the displee bump in this spike are the whole blocker; the decoder
opcode catch-up is the first task of the Phase 1.5 PR itself, with the exact opcode list above and upstream's
codecs as the reference. Then, in order: regenerate RSCM, and evaluate index 24's gameval tables as the
RSCM source (they are plain-text name lists, which is what RSCM wants); drop XTEA (OpenRS2 lists no keys after
build 236, and 241 ships without `xteas.json`); bump `alter.osrsRevision`/`alter.rsprotVersion`; boot and log in.

## Reproduce

```bash
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
./gradlew :plugins:tools:cacheStage -PcacheArgs="--build 241"
./gradlew :plugins:tools:cacheStage -PcacheArgs="--verify ../data/cache-staging/241-2735"
./gradlew :plugins:tools:cacheDryRun -PcacheArgs="../data/cache-staging/241-2735 241"
./gradlew :plugins:filestore:test --tests "dev.openrune.cache.filestore.ReferenceTableTests"
```
