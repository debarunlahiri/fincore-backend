#!/usr/bin/env bash
# Runs inside the "builder" container of docker-compose.dev.yml: builds everything once, signals the service
# containers (by creating .dev-ready), then watches sources and recompiles + triggers reloads.
set -euo pipefail
cd /workspace
# shellcheck source=scripts/dev-lib.sh
source scripts/dev-lib.sh

rm -f .dev-ready
log "initial build (first run downloads dependencies, this can take several minutes)"
mvn -q -B -DskipTests install
touch .dev-ready
log "ready, services may start"

watch_loop "${SERVICES_ALL[@]}"
