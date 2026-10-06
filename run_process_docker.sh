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

# The Dockerfile COPYs target/process-1.0-0.jar rather than building inside the image, so
# "docker-compose build" happily produces a new image around an old jar. `mvn test` does not
# write that jar -- only `mvn package` does -- which is how a deploy can report success, restart
# cleanly, pass its health check, and still be running the code from days ago. That happened, and
# the only clue was a file timestamp nobody looks at. So refuse rather than mislead.
JAR="target/process-1.0-0.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "ERROR: ${JAR} does not exist. Run: mvn package" >&2
  exit 1
fi
NEWER=$(find src/main -newer "${JAR}" -type f -print -quit 2>/dev/null || true)
if [[ -n "${NEWER}" ]]; then
  echo "ERROR: ${JAR} is older than the source (e.g. ${NEWER})." >&2
  echo "       Deploying it would ship stale code. Run: mvn package" >&2
  exit 1
fi

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