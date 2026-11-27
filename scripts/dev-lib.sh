#!/usr/bin/env bash
# Shared helpers for the hot-reload scripts (sourced, not run). Needs bash 3.2+, find, mvn on JDK 21.
#
# How reload works: every service runs with Spring DevTools. A change to a source file is recompiled by
# `mvn compile`, and then <module>/target/classes/.reloadtrigger is touched. DevTools is configured (by the
# scripts / compose file, through environment variables) to restart ONLY when that trigger file changes, so a
# half-finished compile can never restart a service, and only the service whose code changed restarts.

SERVICES_ALL=(auth-service customer-service banking-core payment-service notification-service scheduler-service api-gateway)
SHARED=(common common-data)

log() { printf '\033[36m[watch]\033[0m %s\n' "$*"; }

# true when any file under the given source dir is newer than the stamp file
changed() {
  [ -n "$(find "$1" -type f -newer "$2" -print -quit 2>/dev/null)" ]
}

# Recompile one service and tell its DevTools to restart.
reload_service() {
  local s="$1"
  if mvn -q -B -pl "$s" compile; then
    touch "$s/target/classes/.reloadtrigger"
    log "reloaded $s"
  else
    log "compile of $s FAILED, the running version was left untouched"
  fi
}

# Poll for source changes forever. $@ = the services to keep fresh.
# Polling (not inotify) because file events do not cross Docker Desktop bind mounts reliably.
watch_loop() {
  local services=("$@") stamps m s shared_changed
  stamps="$(mktemp -d)"
  for m in "${SHARED[@]}" "${services[@]}"; do touch "$stamps/$m"; done
  log "watching: ${SHARED[*]} ${services[*]}"
  while sleep 1; do
    shared_changed=false
    for m in "${SHARED[@]}"; do
      if changed "$m/src" "$stamps/$m"; then shared_changed=true; touch "$stamps/$m"; fi
    done
    if $shared_changed; then
      log "shared library changed, rebuilding common + common-data"
      mvn -q -B -DskipTests -pl "$(IFS=,; echo "${SHARED[*]}")" install || { log "shared build FAILED"; continue; }
    fi
    for s in "${services[@]}"; do
      if $shared_changed || changed "$s/src" "$stamps/$s"; then
        touch "$stamps/$s"
        reload_service "$s"
      fi
    done
  done
}

# Environment every dev service runs with: restart only on the trigger file, quick polling, no LiveReload port clash.
export_devtools_env() {
  export SPRING_DEVTOOLS_RESTART_TRIGGER_FILE=.reloadtrigger
  export SPRING_DEVTOOLS_RESTART_POLL_INTERVAL=1s
  export SPRING_DEVTOOLS_RESTART_QUIET_PERIOD=500ms
  export SPRING_DEVTOOLS_LIVERELOAD_ENABLED=false
}
