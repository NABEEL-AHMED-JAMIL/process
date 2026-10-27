#!/usr/bin/env bash
# Runs the end-to-end suites: a whole application booted and driven over real HTTP against the
# local development database, through the real security chain.
#
# The database credentials are read from the running container rather than stored anywhere, so
# nothing secret lives in the repository. Everything each test creates is rolled back.
#
# What it needs (ZT-AI71): the etl-platform stack up (scripts/deploy.sh) with process_app running -- the
# suite reads SPRING_DATASOURCE_USERNAME/PASSWORD, PROCESS_ENCRYPTION_KEY(_ID) and INTERNAL_SERVICE_TOKEN
# from it and never prints them -- plus, published on the host: Postgres on 5433, Kafka's EXTERNAL
# listener on 9093 (9092 advertises kafka:9092, which only containers resolve), Redis on 6379 and
# storage-service on 9120. Postgres, Kafka and storage can be pointed elsewhere with E2E_DATASOURCE_URL,
# E2E_KAFKA_BOOTSTRAP_SERVERS and E2E_STORAGE_URL. Tokens are HS256, minted and verified in the test JVM
# (identity.mode=local) with the profile's own test key; its token-revocation keys in Redis carry an
# e2e: prefix, apart from the live auth:* keys. The suite's Kafka listeners stay off, so it takes no
# events from the live stack's consumer groups. Dependencies come from ~/.m2 (mvn -o). Takes
# ~/.etl-build.lock for the Maven run.
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
# PROCESS_ENCRYPTION_KEY and its id are the current key (MIG-5); LOOKUP_ENCRYPTION_KEY is retired and empty.
export E2E_PROCESS_ENCRYPTION_KEY="${E2E_PROCESS_ENCRYPTION_KEY:-$(docker exec "${CONTAINER}" printenv PROCESS_ENCRYPTION_KEY)}"
export E2E_PROCESS_ENCRYPTION_KEY_ID="${E2E_PROCESS_ENCRYPTION_KEY_ID:-$(docker exec "${CONTAINER}" printenv PROCESS_ENCRYPTION_KEY_ID)}"

# The service token process uses with storage-service (MIG-70): the suite's storage calls go there too.
export E2E_INTERNAL_SERVICE_TOKEN="${E2E_INTERNAL_SERVICE_TOKEN:-$(docker exec "${CONTAINER}" printenv INTERNAL_SERVICE_TOKEN)}"

# Checked after the fact, because the captures above cannot fail loudly on their own: the exit
# status of `export X=$(...)` is export's own, so a printenv that found nothing -- a variable the
# image does not define, a container that stopped between the check above and here -- leaves an
# empty value behind and sails straight past `set -e`. Empty is exactly the state the note above
# says is not worth debugging, so it is refused here rather than diagnosed later.
for required in E2E_DATASOURCE_USERNAME E2E_DATASOURCE_PASSWORD E2E_PROCESS_ENCRYPTION_KEY E2E_PROCESS_ENCRYPTION_KEY_ID \
    E2E_INTERNAL_SERVICE_TOKEN; do
  if [[ -z "${!required}" ]]; then
    echo "ERROR: ${required} came back empty from container '${CONTAINER}'." >&2
    echo "       Set it yourself, or start a stack that defines it." >&2
    exit 1
  fi
done

# One Maven build at a time on this machine (~/.etl-build.lock, as every build script here). A caller that
# already holds the lock sets E2E_BUILD_LOCK_HELD=1.
if [[ "${E2E_BUILD_LOCK_HELD:-0}" != "1" ]]; then
  until mkdir ~/.etl-build.lock 2>/dev/null; do echo "waiting for ~/.etl-build.lock" >&2; sleep 15; done
  trap 'rmdir ~/.etl-build.lock 2>/dev/null || true' EXIT
fi

echo "Running the end-to-end suites against ${E2E_DATASOURCE_URL:-jdbc:postgresql://localhost:5433/etl_job}," \
  "Kafka ${E2E_KAFKA_BOOTSTRAP_SERVERS:-localhost:9093}, storage ${E2E_STORAGE_URL:-http://localhost:9120}"
# SPRING_DATASOURCE_* in the caller's shell would outrank the e2e profile's datasource and point the suite elsewhere.
env -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD \
  mvn -o test -Dtest='*E2EIT,HarnessSmokeIT' "$@"
