#!/usr/bin/env bash
# Local Hytale dev server for Orbis Arena.
#
#   dev/server.sh start      build, install the plugin, start the server in the background
#   dev/server.sh stop       save and stop
#   dev/server.sh restart
#   dev/server.sh reload     rebuild and reload only the plugin (server keeps running)
#   dev/server.sh cmd <...>  send a console command, e.g. dev/server.sh cmd arena status
#   dev/server.sh logs       follow the server log
#   dev/server.sh status
#
# Connect the Hytale client to 127.0.0.1:5524 (PORT; 5520–5523 belong to the other mods).
# BIND=0.0.0.0 dev/server.sh restart opens it to the local network (default: this machine only).
# The server jar is copied from the launcher
# installation whenever its version changes, so it always matches the client. Everything
# the server writes lives in run/ (ignored by git). The server must not run inside the
# launcher folder (exit code 11), only Assets.zip is read from there.
#
# AUTH_MODE defaults to "authenticated": the release client refuses the insecure
# development flow ("development flow not allowed"). Once per run/ folder:
#   dev/server.sh cmd auth persistence Encrypted
#   dev/server.sh cmd auth login device     (confirm the code from the log in the browser)
# The tokens then live encrypted in run/auth.enc and survive restarts.
# The JVM listens for a debugger on 127.0.0.1:5009 (DEBUG_PORT; IntelliJ: "Debug dev server").
#
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN=$ROOT/run
GAME="$HOME/Library/Application Support/Hytale/install/release/package/game/latest"
JAVA="$(/usr/libexec/java_home -v 25)/bin/java"
PLUGIN_ID=de.hymods:OrbisArena
AUTH_MODE=${AUTH_MODE:-authenticated}
PORT=${PORT:-5524}
BIND=${BIND:-127.0.0.1}
DEBUG_PORT=${DEBUG_PORT:-5009}
FIFO=$RUN/console.fifo
PIDFILE=$RUN/server.pid
LOG=$RUN/server.log

die() { echo "server.sh: $*" >&2; exit 1; }

jar_version() {
  unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' | sed -n 's/^Implementation-Version: //p'
}

running() {
  [[ -f $PIDFILE ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

sync_server_jar() {
  [[ -f "$GAME/Server/HytaleServer.jar" ]] || die "no server jar in the launcher installation"
  local want have
  want=$(jar_version "$GAME/Server/HytaleServer.jar")
  have=$(jar_version "$RUN/HytaleServer.jar" 2>/dev/null || true)
  if [[ $want != "$have" ]]; then
    echo "Server jar ${have:-none} -> $want"
    cp "$GAME/Server/HytaleServer.jar" "$RUN/HytaleServer.jar"
  fi
}

install_plugin() {
  (cd "$ROOT" && ./mvnw -q -B package -DskipTests)
  local jar
  jar=$(ls "$ROOT"/target/OrbisArena-*.jar | head -1)
  mkdir -p "$RUN/mods"
  # Replace atomically: the running server may still have the old jar open.
  cp "$jar" "$RUN/mods/.OrbisArena.jar.tmp"
  mv -f "$RUN/mods/.OrbisArena.jar.tmp" "$RUN/mods/OrbisArena.jar"
}

send() {
  running || die "server is not running"
  printf '%s\n' "$*" >"$FIFO"
}

start() {
  running && die "already running (pid $(cat "$PIDFILE"))"
  mkdir -p "$RUN"
  sync_server_jar
  install_plugin
  [[ -p $FIFO ]] || { rm -f "$FIFO"; mkfifo "$FIFO"; }
  echo "--- start $(date '+%F %T') (auth-mode $AUTH_MODE) ---" >>"$LOG"
  # The console reads from the FIFO; opening it read-write keeps it from ever hitting EOF.
  (
    cd "$RUN"
    exec 3<>"$FIFO"
    exec "$JAVA" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$DEBUG_PORT \
      -jar HytaleServer.jar \
      --assets "$GAME/Assets.zip" \
      --bind "$BIND:$PORT" \
      --auth-mode "$AUTH_MODE" \
      --disable-sentry \
      --allow-op \
      <&3 >>"$LOG" 2>&1
  ) &
  echo $! >"$PIDFILE"
  echo "Started (pid $!), log: $LOG"
}

stop() {
  running || { echo "not running"; return 0; }
  local pid
  pid=$(cat "$PIDFILE")
  send stop
  for _ in $(seq 1 90); do
    kill -0 "$pid" 2>/dev/null || { rm -f "$PIDFILE"; echo "stopped"; return 0; }
    sleep 1
  done
  echo "no clean stop after 90 s, sending SIGTERM"
  kill "$pid" 2>/dev/null || true
  rm -f "$PIDFILE"
}

case ${1:-} in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  reload) install_plugin; send plugin reload "$PLUGIN_ID" ;;
  cmd) shift; send "$@" ;;
  logs) tail -n 50 -f "$LOG" ;;
  status) if running; then echo "running (pid $(cat "$PIDFILE"))"; else echo "not running"; fi ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
