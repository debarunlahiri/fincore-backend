#!/usr/bin/env bash
# Run the services on your machine with hot reload: edit a file, save, and only that service restarts.
#
#   scripts/dev-watch.sh                      all 7 services
#   scripts/dev-watch.sh banking-core         just one (or several: scripts/dev-watch.sh auth-service api-gateway)
#
# Needs: JDK 21 for Maven (check `mvn -v`), and the infrastructure running:
#   docker compose up -d postgres kafka redis jaeger
# Stop everything with Ctrl+C.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/dev-lib.sh
source scripts/dev-lib.sh

if [ "$#" -gt 0 ]; then SERVICES=("$@"); else SERVICES=("${SERVICES_ALL[@]}"); fi

if ! mvn -v 2>/dev/null | grep -q "Java version: 21"; then
  echo "Maven is not running on JDK 21. Point JAVA_HOME at a JDK 21 (see 'mvn -v') and try again." >&2
  exit 1
fi

trap 'echo; log "stopping"; kill 0' EXIT INT TERM
export_devtools_env

log "building shared libraries and all modules once (first run takes a few minutes)"
mvn -q -B -DskipTests install

for s in "${SERVICES[@]}"; do
  ( mvn -q -B -pl "$s" spring-boot:run 2>&1 | sed -u "s/^/[$s] /" ) &
done

watch_loop "${SERVICES[@]}"
