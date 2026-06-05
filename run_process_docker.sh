#!/usr/bin/env zsh
set -euo pipefail

# Run only the process_app service (docker-compose will start dependencies as needed)
# Adjust COMPOSE_FILE if your compose file path is different.
COMPOSE_FILE="docker-compose.yml"
SERVICE_NAME="process_app"
HEALTH_URL="http://localhost:9098/api/v1/actuator/health"
START_TIMEOUT=300   # seconds
SLEEP_INTERVAL=5    # seconds

# Move to repository root (script assumed placed in project root or invoked from there)
# cd "$(dirname "$0")/.."   # uncomment if script is inside scripts/ and you want to cd to project root

echo "Building images (if needed) and starting ${SERVICE_NAME}..."
docker-compose -f "${COMPOSE_FILE}" build "${SERVICE_NAME}"
docker-compose -f "${COMPOSE_FILE}" up -d "${SERVICE_NAME}"

echo "Waiting for ${SERVICE_NAME} to become healthy (polling ${HEALTH_URL})..."
start_time=$(date +%s)
while true; do
  if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
    echo "${SERVICE_NAME} is responding at ${HEALTH_URL}"
    break
  fi
  now=$(date +%s)
  elapsed=$(( now - start_time ))
  if (( elapsed >= START_TIMEOUT )); then
    echo "Timed out waiting for ${SERVICE_NAME} after ${START_TIMEOUT}s."
    echo "Check container logs with: docker-compose -f ${COMPOSE_FILE} logs ${SERVICE_NAME}"
    exit 1
  fi
  echo "Not ready yet... sleeping ${SLEEP_INTERVAL}s"
  sleep ${SLEEP_INTERVAL}
done

echo "Done. To view logs: docker-compose -f ${COMPOSE_FILE} logs -f ${SERVICE_NAME}"