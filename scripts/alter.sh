#!/usr/bin/env bash
# Alter2 server launcher (Linux, macOS, Git Bash).
#
#   scripts/alter.sh up [--build]      start in the background (builds the distribution if missing)
#   scripts/alter.sh down [ticks]      graceful stop: countdown, everyone logged out and saved, then exit
#   scripts/alter.sh restart [ticks]   graceful restart
#   scripts/alter.sh status            health of the running server
#   scripts/alter.sh logs [-f]         server log (data/logs/alter.log)
#
# The server writes data/run/server.json (pid, admin port, token) while it runs; this script talks to its
# local admin API (127.0.0.1 only). A small supervisor loop restarts the server when it exits with code 75
# (restart requested by `restart` or ::update) and stops on any other exit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/data/run"
RUN_FILE="$RUN_DIR/server.json"
SUPERVISOR_PID="$RUN_DIR/supervisor.pid"
LOG_DIR="$ROOT/data/logs"
LIB="$ROOT/game-server/build/install/game-server/lib"
MAIN_CLASS=org.alter.game.Launcher
RESTART_EXIT_CODE=75

field() { sed -n "s/.*\"$1\" *: *\"\{0,1\}\([^\",]*\)\"\{0,1\}.*/\1/p" "$RUN_FILE" | head -1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) # Git Bash: java.exe needs a Windows classpath, and the run file holds a Windows pid
    WINDOWS=1; CP="$(cygpath -w "$LIB")\*"
    alive() { [ -n "${1:-}" ] && tasklist //FI "PID eq $1" //NH 2>/dev/null | grep -qw "$1"; } ;;
  *)
    WINDOWS=0; CP="$LIB/*"
    alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; } ;;
esac

stop_pid() { if [ "$WINDOWS" = 1 ]; then taskkill //PID "$1" //F > /dev/null 2>&1 || true; else kill -TERM "$1" 2>/dev/null || true; fi; }

server_pid() { [ -f "$RUN_FILE" ] && field pid || true; }

api() { # api METHOD PATH
  local port token
  port="$(field adminPort)"; token="$(field adminToken)"
  curl -fsS -X "$1" -H "Authorization: Bearer $token" "http://127.0.0.1:$port$2"
}

java_bin() { # Java 17+: JAVA_HOME first, then PATH
  local candidate major
  for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    major="$("$candidate" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [ "${major:-0}" -ge 17 ]; then echo "$candidate"; return 0; fi
  done
  echo "Java 17 or newer is required; set JAVA_HOME to a JDK 17." >&2; return 1
}

build() {
  echo "Building the server distribution..."
  (cd "$ROOT" && ./gradlew --console=plain -q :game-server:installDist)
}

up() {
  if alive "$(server_pid)"; then echo "Already running (pid $(server_pid))."; return 0; fi
  if [ "${1:-}" = "--build" ] || [ ! -d "$LIB" ]; then build; fi
  local java opts="${ALTER_JAVA_OPTS:--Xmx3g}"
  java="$(java_bin)"
  mkdir -p "$RUN_DIR" "$LOG_DIR"
  rm -f "$RUN_FILE"
  # The server resolves ../data and ../game.yml, so it runs from game-server/.
  nohup bash -c "
    cd '$ROOT/game-server'
    while true; do
      '$java' $opts -cp '$CP' $MAIN_CLASS >> '$LOG_DIR/console.log' 2>&1 && code=0 || code=\$?
      echo \"[\$(date -u +%FT%TZ)] server exited with code \$code\" >> '$LOG_DIR/console.log'
      [ \"\$code\" -eq $RESTART_EXIT_CODE ] || break
    done
    rm -f '$SUPERVISOR_PID'
  " > /dev/null 2>&1 &
  echo $! > "$SUPERVISOR_PID"
  echo -n "Starting"
  for _ in $(seq 1 180); do
    if [ -f "$RUN_FILE" ] && api GET /health > /dev/null 2>&1; then echo; status; return 0; fi
    kill -0 "$(cat "$SUPERVISOR_PID" 2>/dev/null)" 2>/dev/null || { echo; echo "Server exited during startup; see $LOG_DIR/console.log"; return 1; }
    echo -n "."; sleep 1
  done
  echo; echo "Server did not report healthy within 180s; see $LOG_DIR/console.log"; return 1
}

stop_request() { # stop_request TICKS RESTART
  local pid; pid="$(server_pid)"
  if ! alive "$pid"; then echo "Not running."; return 0; fi
  api POST "/shutdown?ticks=$1&restart=$2" > /dev/null || { echo "Admin API unreachable; sending SIGTERM (the shutdown hook still saves players)."; stop_pid "$pid"; }
}

down() {
  local ticks="${1:-0}" pid; pid="$(server_pid)"
  stop_request "$ticks" false
  alive "$pid" || return 0
  echo -n "Stopping"
  local limit=$(( ticks * 600 / 1000 + 60 ))
  for _ in $(seq 1 "$limit"); do alive "$pid" || { echo; echo "Stopped."; return 0; }; echo -n "."; sleep 1; done
  echo; echo "Still running after ${limit}s; sending SIGTERM."; stop_pid "$pid"
}

restart() { stop_request "${1:-0}" true; echo "Restart requested; the supervisor starts it again. Check with: $0 status"; }

status() {
  local pid; pid="$(server_pid)"
  if alive "$pid"; then api GET /health || echo "Running (pid $pid) but the admin API did not answer."; echo; else echo "Stopped."; fi
}

logs() { if [ "${1:-}" = "-f" ]; then tail -n 50 -f "$LOG_DIR/alter.log"; else tail -n 200 "$LOG_DIR/alter.log"; fi; }

case "${1:-}" in
  up) shift; up "$@" ;;
  down) shift; down "$@" ;;
  restart) shift; restart "$@" ;;
  status) status ;;
  logs) shift; logs "$@" ;;
  build) build ;;
  *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
