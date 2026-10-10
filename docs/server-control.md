# Starting and stopping the server

The server can be stopped safely in every supported way: each one logs every player out and saves them.

| How | Start | Graceful stop | Restart |
|---|---|---|---|
| Scripts (dev, any OS) | `scripts/alter.sh up` / `.\scripts\alter.ps1 up` | `down [ticks]` | `restart [ticks]` or `::update [ticks]` in game |
| Docker | `docker compose up -d --build` | `docker compose stop` | `::update [ticks]`, or `docker compose restart` |
| systemd (Linux) | `systemctl start alter2` | `systemctl stop alter2` | `::update [ticks]`, `alter.sh restart`, or `systemctl restart alter2` |
| IntelliJ / `gradlew :game-server:run` | Run | Stop button | (no supervisor) |

## The scripts

`scripts/alter.sh` (Linux, macOS, Git Bash) and `scripts/alter.ps1` (Windows PowerShell) take the same commands:

| Command | What it does |
|---|---|
| `up` (`--build` / `-Build`) | Builds the distribution if it is missing (or when asked), starts the server in the background and waits until it reports healthy. |
| `down [ticks]` | Counts down `ticks` game ticks (default 0) with the reboot timer, logs everyone out and saves, then exits. |
| `restart [ticks]` | Same as `down`, then the supervisor starts the server again. |
| `status` | The server's health as JSON: revision, pid, uptime, cycle, players, npcs, reboot timer, snapshot counts. |
| `logs` (`-f` / `-Follow`) | The server log, `data/logs/alter.log`. |
| `build` | `gradlew :game-server:installDist`. |

The scripts need Java 17 (`JAVA_HOME`, or `java` on `PATH`). JVM options come from `ALTER_JAVA_OPTS` (default `-Xmx3g`). Console output goes to `data/logs/console.log`, including one line per server exit with its code.

They run `java` on `game-server/build/install/game-server/lib/*` directly, from `game-server/`. Gradle's start script is not used because on Windows its classpath is too long for `cmd.exe`.

## Exit codes

| Code | Meaning | Supervisor, Docker (`on-failure`), systemd |
|---|---|---|
| 0 | Graceful stop (`down`, `/shutdown`) | Stay down |
| 75 | Restart requested (`restart`, `::update`) | Start again |
| 143 | Stopped by SIGTERM (Ctrl+C, `docker stop`, `systemctl stop`); players were saved by the shutdown hook | Stay down |
| other | Crash or fatal boot check | Scripts: stay down. Docker and systemd: start again |

## The admin API

While the server runs it writes `data/run/server.json` with its pid, the admin port and a fresh random token, and deletes it on exit. The scripts and the Dev Cockpit find the server through this file.

The API listens on `127.0.0.1` only (port 43595 by default, `admin:` in `data/cfg/infrastructure.yml`) and every request needs `Authorization: Bearer <token>`.

| Request | Effect |
|---|---|
| `GET /health` | Health JSON. `status` is `up`, or `stalled` (HTTP 503) when the game thread does not answer within 2 s. |
| `POST /shutdown?ticks=N&restart=true\|false` | Graceful stop or restart after N ticks (0–6000). 409 if one is already scheduled. |
| `POST /wiki/reload` | Re-reads the committed wiki snapshot from disk (no network) and swaps it in, like `::wikisync` without the sync. |
| `GET /events` | Server-sent events: `missing` (a missing-content key seen for the first time), `lifecycle` (shutdown/restart scheduled), and a heartbeat every 15 s. |

## Docker

`docker compose up -d --build` builds the image (JDK 17 build stage, JRE runtime) and mounts `./data` and `./game.yml` from the checkout. Before the first start, `data/cache` must hold the staged revision 241 cache (no `xteas.json` is needed from revision 237 on) and `game.yml` must be copied from `game.example.yml`.

The admin API stays inside the container, so use `docker compose stop|restart|logs` there, not the scripts. On Docker Desktop for Windows or macOS the first boot can take a few minutes because the cache is read through the bind mount.

## systemd

`deploy/systemd/alter2.service` runs the installed distribution from `/opt/alter2`. Adjust `User` and the paths, then `systemctl enable --now alter2`. `alter.sh status|down|restart` work alongside it; don't use `alter.sh up` with it.
