#!/usr/bin/env bash
# One command to start the whole backend: start Docker if needed, release old containers, free the ports,
# build and start. If the build fails because Docker's image store is corrupted, it repairs it and retries.
#
#   scripts/up.sh              build and start everything in the background
#   scripts/up.sh --no-build   start without rebuilding the images
#
# Anything extra is passed to `docker compose up -d`.
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKER_WAIT_SECONDS="${DOCKER_WAIT_SECONDS:-180}"

docker_ready() { docker info >/dev/null 2>&1; }

# Waits until the Docker daemon answers, printing a dot every few seconds.
wait_for_docker() {
  local waited=0
  while ! docker_ready; do
    if [ "$waited" -ge "$DOCKER_WAIT_SECONDS" ]; then
      echo >&2
      echo "Docker did not become ready within ${DOCKER_WAIT_SECONDS}s. Open Docker Desktop, wait for it to finish starting, and run this again." >&2
      exit 1
    fi
    printf '.'
    sleep 3
    waited=$((waited + 3))
  done
  [ "$waited" -gt 0 ] && echo
  return 0
}

# Starts Docker Desktop when it is not running (macOS), then waits for it.
ensure_docker() {
  if docker_ready; then return 0; fi
  case "$(uname -s)" in
    Darwin)
      echo "==> Docker is not running, opening Docker Desktop"
      open -a Docker 2>/dev/null || open -a "Docker Desktop" 2>/dev/null || {
        echo "Could not find Docker Desktop. Install it from https://www.docker.com/products/docker-desktop and try again." >&2
        exit 1
      }
      ;;
    Linux)
      echo "==> Docker is not running, starting the service"
      sudo systemctl start docker 2>/dev/null || systemctl --user start docker-desktop 2>/dev/null || {
        echo "Could not start Docker. Start it yourself and try again." >&2
        exit 1
      }
      ;;
    *)
      echo "Docker is not running. Start it and try again." >&2
      exit 1
      ;;
  esac
  printf '==> waiting for Docker to be ready'
  wait_for_docker
}

# Quits and reopens Docker Desktop (macOS), used when pruning alone did not fix the image store.
restart_docker() {
  echo "==> restarting Docker Desktop"
  if [ "$(uname -s)" = "Darwin" ]; then
    osascript -e 'quit app "Docker"' 2>/dev/null || true
    for _ in $(seq 1 30); do pgrep -x "Docker Desktop" >/dev/null 2>&1 || pgrep -x "com.docker.backend" >/dev/null 2>&1 || break; sleep 1; done
    sleep 3
    open -a Docker 2>/dev/null || open -a "Docker Desktop"
  else
    sudo systemctl restart docker
  fi
  printf '==> waiting for Docker to be ready'
  wait_for_docker
}

# The image store is corrupted when the build ends with e.g.
#   "failed to prepare extraction snapshot ... parent snapshot ... does not exist: not found"
store_is_corrupt() {
  grep -qE "parent snapshot .* does not exist|failed to prepare extraction snapshot|failed to get digest .*: not found|content digest .*: not found|no such image" "$1"
}

# Clears the build cache and this project's images so the next build starts from clean layers.
repair_image_store() {
  echo "==> repairing Docker's image store (clearing the build cache and this project's images)"
  docker compose down --remove-orphans >/dev/null 2>&1 || true
  docker builder prune -af >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true
  # shellcheck disable=SC2046
  docker rmi -f $(docker compose config --images 2>/dev/null | sort -u) >/dev/null 2>&1 || true
}

ensure_docker

BUILD="--build"
ARGS=()
for a in "$@"; do
  if [ "$a" = "--no-build" ]; then BUILD=""; else ARGS+=("$a"); fi
done

echo "==> stopping any previous FinCore containers"
docker compose down --remove-orphans

echo "==> freeing the backend ports"
scripts/free-ports.sh

LOG="$(mktemp -t fincore-up.XXXXXX)"
trap 'rm -f "$LOG"' EXIT

start() {
  # shellcheck disable=SC2086
  docker compose up -d $BUILD ${ARGS[@]+"${ARGS[@]}"} 2>&1 | tee "$LOG"
}

echo "==> starting FinCore"
if ! start; then
  if store_is_corrupt "$LOG"; then
    echo
    echo "==> the build failed because Docker's image store is damaged; fixing it and trying again"
    repair_image_store
    if ! start; then
      if store_is_corrupt "$LOG"; then
        restart_docker
        repair_image_store
        start
      else
        exit 1
      fi
    fi
  else
    exit 1
  fi
fi

echo
echo "Gateway:  http://localhost:9780      Health: http://localhost:9781/actuator/health"
echo "Logs:     docker compose logs -f      Stop:   docker compose down"
