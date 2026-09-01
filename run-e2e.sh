#!/usr/bin/env bash
# Runs the end-to-end suites: a whole application booted and driven over real HTTP against the
# local development database, through the real security chain.
#
# The database credentials are read from the running container rather than stored anywhere, so
# nothing secret lives in the repository. Everything each test creates is rolled back.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${PROCESS_CONTAINER:-process_app}"
if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"; then
  echo "ERROR: container '${CONTAINER}' is not running -- the suite reads the database" >&2
  echo "       credentials from it. Start the stack, or set E2E_DATASOURCE_USERNAME and" >&2
  echo "       E2E_DATASOURCE_PASSWORD yourself." >&2
  exit 1
fi

export E2E_DATASOURCE_USERNAME="${E2E_DATASOURCE_USERNAME:-$(docker exec "${CONTAINER}" printenv SPRING_DATASOURCE_USERNAME)}"
export E2E_DATASOURCE_PASSWORD="${E2E_DATASOURCE_PASSWORD:-$(docker exec "${CONTAINER}" printenv SPRING_DATASOURCE_PASSWORD)}"

echo "Running the end-to-end suites against ${E2E_DATASOURCE_URL:-jdbc:postgresql://localhost:5433/etl_job}"
exec mvn -o test -Dtest='*E2EIT,HarnessSmokeIT' "$@"
