---
paths:
  - "game-server/**"
---

# Editing game-server

- game-server is the engine. Change it only for a specific, justified fix; prefer a hook that content in game-plugins can use.
- One fix per commit, the reason in the commit message, and a regression test in `game-server/src/test`.
- No refactors, renames or reformatting in the same change. No new dependencies.
- game-server cannot import game-api or game-plugins (they depend on it). Shared constants it needs must live in game-server or util.
- Anything that touches player state runs on the game thread. Use `GameService.submitGameThreadJob` from other threads.
- `net.rsprot` belongs in the network layer (`org.alter.game.rsprot`, `message/handler`, info/zone encoders). Don't spread it further.
