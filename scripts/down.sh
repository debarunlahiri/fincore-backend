#!/usr/bin/env bash
# Stops everything FinCore started on this machine: the Cloudflare tunnel, the containers, services run straight on
# the Mac (scripts/dev-watch.sh, an IDE, mvn spring-boot:run) and finally Docker Desktop itself.
#
#   scripts/down.sh                  stop everything and quit Docker Desktop
#   scripts/down.sh --keep-docker    stop everything but leave Docker Desktop running
#   scripts/down.sh --volumes        also delete the database/Kafka volumes (ALL local data, asks first)
#
# Safe to run when nothing is running.
set -uo pipefail
cd "$(dirname "$0")/.."

KEEP_DOCKER=false
VOLUMES=false
for a in "$@"; do
  case "$a" in
    --keep-docker) KEEP_DOCKER=true ;;
    --volumes) VOLUMES=true ;;
    -h|--help) sed -n 2,10p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $a (try --help)" >&2; exit 2 ;;
  esac
done

if $VOLUMES; then
  printf 'This deletes ALL local FinCore data (databases, Kafka). Type "delete" to continue: '
  read -r answer
  [ "$answer" = "delete" ] || { echo "cancelled, nothing was stopped."; exit 1; }
fi

echo "==> stopping the Cloudflare tunnel"
if pgrep -f "cloudflared.*(localhost:9780|tunnel --no-autoupdate run)" >/dev/null 2>&1; then
  pkill -TERM -f "cloudflared.*(localhost:9780|tunnel --no-autoupdate run)" 2>/dev/null
  sleep 1
  echo "    stopped"
else
  echo "    not running"
fi

echo "==> stopping the containers"
if docker info >/dev/null 2>&1; then
  if $VOLUMES; then
    docker compose down --remove-orphans --volumes
  else
    docker compose down --remove-orphans
  fi
  # the hot-reload setup is a separate compose file
  docker compose -f docker-compose.yml -f docker-compose.dev.yml down --remove-orphans >/dev/null 2>&1 || true
else
  echo "    Docker is not running, so there are no containers"
fi

echo "==> stopping FinCore services started on this Mac (not the databases)"
FREE_PORTS_EXCLUDE="5432 6379 9092 16686 4318" scripts/free-ports.sh || true

if $KEEP_DOCKER; then
  echo "==> leaving Docker Desktop running (--keep-docker)"
  exit 0
fi

echo "==> quitting Docker Desktop"
case "$(uname -s)" in
  Darwin)
    if pgrep -f "Docker Desktop|com.docker.backend" >/dev/null 2>&1; then
      osascript -e 'quit app "Docker"' >/dev/null 2>&1 || true
      for _ in $(seq 1 30); do
        pgrep -f "Docker Desktop|com.docker.backend" >/dev/null 2>&1 || break
        sleep 1
      done
      if pgrep -f "Docker Desktop|com.docker.backend" >/dev/null 2>&1; then
        echo "    Docker Desktop is still shutting down; it will finish by itself"
      else
        echo "    quit"
      fi
    else
      echo "    not running"
    fi
    ;;
  Linux)
    sudo systemctl stop docker docker.socket 2>/dev/null && echo "    stopped" || echo "    could not stop the Docker service (needs sudo)"
    ;;
  *)
    echo "    quit Docker yourself on this system"
    ;;
esac

echo "done"
