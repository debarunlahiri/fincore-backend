#!/usr/bin/env bash
# One command to start the whole backend: release old containers, free the ports, build and start.
#
#   scripts/up.sh              build and start everything in the background
#   scripts/up.sh --no-build   start without rebuilding the images
#
# Anything extra is passed to `docker compose up -d`.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

BUILD="--build"
ARGS=()
for a in "$@"; do
  if [ "$a" = "--no-build" ]; then BUILD=""; else ARGS+=("$a"); fi
done

echo "==> stopping any previous FinCore containers"
docker compose down --remove-orphans

echo "==> freeing the backend ports"
scripts/free-ports.sh

echo "==> starting FinCore"
# shellcheck disable=SC2086
docker compose up -d $BUILD ${ARGS[@]+"${ARGS[@]}"}

echo
echo "Gateway:  http://localhost:9780      Health: http://localhost:9781/actuator/health"
echo "Logs:     docker compose logs -f      Stop:   docker compose down"
