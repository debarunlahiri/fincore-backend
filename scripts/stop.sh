#!/usr/bin/env bash
# Stops the whole backend: the file watcher, the services, the databases and the tunnel.
# Docker Desktop is left running (so the next start is fast).
#
#   scripts/stop.sh                  stop everything, keep Docker Desktop running
#   scripts/stop.sh --quit-docker    also quit Docker Desktop
#   scripts/stop.sh --volumes        also delete the database/Kafka volumes (ALL local data, asks first)
set -uo pipefail
cd "$(dirname "$0")/.."

ARGS=(--keep-docker)
for a in "$@"; do
  case "$a" in
    --quit-docker) ARGS=("${ARGS[@]/--keep-docker}") ;;
    -h|--help) sed -n 2,8p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done
rm -f .dev-ready
# drop the empty string left by --quit-docker
CLEAN=(); for x in "${ARGS[@]}"; do [ -n "$x" ] && CLEAN+=("$x"); done
exec scripts/down.sh ${CLEAN[@]+"${CLEAN[@]}"}
