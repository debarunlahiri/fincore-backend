#!/usr/bin/env bash
# Live, colored logs of the running backend (each service gets its own color, log levels are colored too).
#
#   scripts/logs.sh                  everything
#   scripts/logs.sh banking-core     one or more services
#   scripts/logs.sh --since 5m       extra arguments go to `docker compose logs`
set -uo pipefail
cd "$(dirname "$0")/.."

FILES=(-f docker-compose.yml)
# use the hot-reload file when that stack is the one running
if docker compose -f docker-compose.yml -f docker-compose.dev.yml ps --services --filter status=running 2>/dev/null | grep -qx builder; then
  FILES=(-f docker-compose.yml -f docker-compose.dev.yml)
fi

exec docker compose "${FILES[@]}" --ansi always logs -f --tail=100 "$@"
