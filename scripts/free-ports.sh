#!/usr/bin/env bash
# Frees every host port the FinCore backend needs by stopping whatever is listening on it
# (a local Redis or Postgres, a service left over from an IDE or `mvn spring-boot:run`, an old container...).
#
#   scripts/free-ports.sh              stop the processes holding the ports
#   scripts/free-ports.sh --dry-run    only list them
#   FREE_PORTS_EXCLUDE="5432 6379" scripts/free-ports.sh     leave those ports alone
#
# Docker's own port proxy is never touched: containers of this project are released by `docker compose down`.
set -uo pipefail

# gateway, gateway health, the 6 services, Postgres, Redis, Kafka, Jaeger UI, Jaeger OTLP
PORTS=(9780 9781 8081 8082 8083 8084 8085 8086 5432 6379 9092 16686 4318)
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true
EXCLUDE=" ${FREE_PORTS_EXCLUDE:-} "

if ! command -v lsof >/dev/null 2>&1; then
  echo "lsof is required (it ships with macOS and most Linux distributions)." >&2
  exit 1
fi

owners() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | sort -u; }

# A Homebrew service restarts itself after a plain kill, so stop it through brew when we can.
stop_brew_service() {
  command -v brew >/dev/null 2>&1 || return 1
  local pattern
  case "$1" in
    *redis*) pattern="redis" ;;
    *postgres*) pattern="postgres" ;;
    *kafka*) pattern="kafka" ;;
    *) return 1 ;;
  esac
  local svc
  svc="$(brew services list 2>/dev/null | awk -v p="$pattern" '$2=="started" && $1 ~ p {print $1; exit}')"
  [ -n "$svc" ] || return 1
  echo "  stopping Homebrew service '$svc'"
  brew services stop "$svc" >/dev/null 2>&1
}

freed=0
blocked=0
for port in "${PORTS[@]}"; do
  case "$EXCLUDE" in *" $port "*) echo "port $port: skipped (excluded)"; continue ;; esac
  for pid in $(owners "$port"); do
    name="$(ps -o comm= -p "$pid" 2>/dev/null | xargs basename 2>/dev/null)"
    case "$name" in
      *docker*|*vpnkit*|*Docker*)
        echo "port $port: held by Docker ($name), run 'docker compose down' to release it"
        continue ;;
    esac
    echo "port $port: in use by ${name:-unknown} (pid $pid)"
    $DRY_RUN && continue
    if ! stop_brew_service "$name"; then
      kill -TERM "$pid" 2>/dev/null
    fi
    for _ in 1 2 3 4 5; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "  still running, forcing it to stop"
      kill -KILL "$pid" 2>/dev/null
    fi
  done
  # give the OS a moment, then confirm
  if ! $DRY_RUN; then
    sleep 0.3
    if [ -n "$(owners "$port")" ]; then
      echo "port $port: STILL IN USE. Another user's process? Try: sudo lsof -nP -iTCP:$port -sTCP:LISTEN" >&2
      blocked=$((blocked + 1))
    else
      freed=$((freed + 1))
    fi
  fi
done

if $DRY_RUN; then
  echo "dry run, nothing was stopped"
else
  echo "done: $freed ports free, $blocked still blocked"
  [ "$blocked" -eq 0 ]
fi
