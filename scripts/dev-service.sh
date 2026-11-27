#!/usr/bin/env bash
# Runs inside each service container of docker-compose.dev.yml: waits for the builder, then starts the service
# with Spring DevTools (spring-boot:run). The builder touches this module's .reloadtrigger to restart it.
set -euo pipefail
cd /workspace
MODULE="${1:?usage: dev-service.sh <module>}"

until [ -f .dev-ready ]; do
  echo "[$MODULE] waiting for the initial build..."
  sleep 3
done
exec mvn -q -B -pl "$MODULE" spring-boot:run
