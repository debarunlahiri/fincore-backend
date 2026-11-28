#!/usr/bin/env bash
# Starts the whole backend with a file watcher and live, colored logs.
#
#   scripts/start.sh              hot reload: save a Java file and only that service restarts; logs follow live
#   scripts/start.sh --no-watch   normal images (scripts/up.sh), then follow the logs live
#   scripts/start.sh --detach     start and return, without following the logs
#   scripts/start.sh banking-core auth-service   only these services (and what they need)
#
# Ctrl+C stops following the logs, the services keep running. Stop them with scripts/stop.sh.
# Follow the logs again any time with:  scripts/logs.sh [service]
set -euo pipefail
cd "$(dirname "$0")/.."

WATCH=true
FOLLOW=true
SERVICES=()
for a in "$@"; do
  case "$a" in
    --no-watch) WATCH=false ;;
    --detach) FOLLOW=false ;;
    -h|--help) sed -n 2,11p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option: $a (try --help)" >&2; exit 2 ;;
    *) SERVICES+=("$a") ;;
  esac
done

if ! $WATCH; then
  scripts/up.sh ${SERVICES[@]+"${SERVICES[@]}"}
  $FOLLOW && exec scripts/logs.sh ${SERVICES[@]+"${SERVICES[@]}"}
  exit 0
fi

docker_ready() { docker info >/dev/null 2>&1; }
if ! docker_ready; then
  echo "==> Docker is not running, opening Docker Desktop"
  open -a Docker 2>/dev/null || open -a "Docker Desktop" 2>/dev/null || { echo "Start Docker and try again." >&2; exit 1; }
  printf '==> waiting for Docker to be ready'
  for _ in $(seq 1 90); do docker_ready && break; printf '.'; sleep 2; done
  echo
  docker_ready || { echo "Docker did not start in time." >&2; exit 1; }
fi

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev.yml)

echo "==> stopping any previous FinCore containers"
"${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || docker compose down --remove-orphans
rm -f .dev-ready

echo "==> freeing the backend ports"
scripts/free-ports.sh

echo "==> starting FinCore with the file watcher (the first start downloads Maven dependencies and is slow)"
# the builder compiles every module, then the services start; saving a source file recompiles that module
"${COMPOSE[@]}" up -d ${SERVICES[@]+"${SERVICES[@]}"} builder

echo
echo "Gateway:  http://localhost:9780      Health: http://localhost:9781/actuator/health"
echo "Stop:     scripts/stop.sh"
echo

$FOLLOW || exit 0
exec scripts/logs.sh ${SERVICES[@]+"${SERVICES[@]}"}
