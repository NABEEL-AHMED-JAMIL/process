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

# The same key the application encrypts with, for the same reason the credentials are read here
# rather than written down. Without it the suite boots with an empty key, and every stored
# credential -- including the etl-avatar connection's own secret key -- fails to decrypt, so a
# request that the guard admits is refused a step later by the storage layer instead. That is a
# refusal the tests cannot tell apart from the ones they are asserting about.
export E2E_LOOKUP_ENCRYPTION_KEY="${E2E_LOOKUP_ENCRYPTION_KEY:-$(docker exec "${CONTAINER}" printenv LOOKUP_ENCRYPTION_KEY)}"

# Checked after the fact, because the captures above cannot fail loudly on their own: the exit
# status of `export X=$(...)` is export's own, so a printenv that found nothing -- a variable the
# image does not define, a container that stopped between the check above and here -- leaves an
# empty value behind and sails straight past `set -e`. Empty is exactly the state the note above
# says is not worth debugging, so it is refused here rather than diagnosed later.
for required in E2E_DATASOURCE_USERNAME E2E_DATASOURCE_PASSWORD E2E_LOOKUP_ENCRYPTION_KEY; do
  if [[ -z "${!required}" ]]; then
    echo "ERROR: ${required} came back empty from container '${CONTAINER}'." >&2
    echo "       Set it yourself, or start a stack that defines it." >&2
    exit 1
  fi
done

echo "Running the end-to-end suites against ${E2E_DATASOURCE_URL:-jdbc:postgresql://localhost:5433/etl_job}"
exec mvn -o test -Dtest='*E2EIT,HarnessSmokeIT' "$@"
