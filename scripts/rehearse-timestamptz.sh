#!/usr/bin/env bash
# MIG-96 and MIG-29: rehearse V100/V101 (timestamptz) and V102 (tenant_id) on a full copy of a long-lived etl_job,
# then drop the copy.
#
#   scripts/rehearse-timestamptz.sh [source-db]        (default etl_job)
#
# The source is only read (pg_dump). The copy is made on the same server under a new name, migrated, checked and
# rolled back by TimestamptzRehearsal and TenantIdRehearsal (each on its own, one after the other), and dropped whatever happens. Credentials come from the
# container's own environment and are never printed. POSTGRES_CONTAINER and NOTIFICATIONS_TEST_DB_URL override the
# local defaults (postgres_db, jdbc:postgresql://localhost:5433/postgres).
set -euo pipefail

SOURCE_DB=${1:-etl_job}
CONTAINER=${POSTGRES_CONTAINER:-postgres_db}
COPY="etl_job_rehearsal_$(date +%s)"
cd "$(dirname "$0")/.."

PGUSER_IN=$(docker exec "$CONTAINER" printenv POSTGRES_USER)
export NOTIFICATIONS_TEST_DB_USER="$PGUSER_IN"
NOTIFICATIONS_TEST_DB_PASSWORD=$(docker exec "$CONTAINER" printenv POSTGRES_PASSWORD)
export NOTIFICATIONS_TEST_DB_PASSWORD
export NOTIFICATIONS_TEST_DB_URL=${NOTIFICATIONS_TEST_DB_URL:-jdbc:postgresql://localhost:5433/postgres}

docker exec "$CONTAINER" psql -q -U "$PGUSER_IN" -d postgres -c "CREATE DATABASE $COPY"
trap 'docker exec "$CONTAINER" psql -q -U "$PGUSER_IN" -d postgres -c "DROP DATABASE IF EXISTS $COPY"' EXIT
docker exec "$CONTAINER" sh -c "pg_dump -U \"\$POSTGRES_USER\" --no-owner --no-privileges \"$SOURCE_DB\" | psql -q -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $COPY" > /dev/null
echo "copied $SOURCE_DB to $COPY; rehearsing"

# The changelog parameters the source was built with (V70.2's; they are part of its checksum): the running
# process_app's, when there is one, else the dev defaults.
if [ -z "${TIMESTAMPTZ_REHEARSAL_PARAMS:-}" ]; then
  BOOTSTRAP=$(docker exec process_app printenv SPRING_KAFKA_BOOTSTRAP_SERVERS 2>/dev/null || echo localhost:9092)
  PROTOCOL=$(docker exec process_app printenv SPRING_KAFKA_SECURITY_PROTOCOL 2>/dev/null || echo PLAINTEXT)
  export TIMESTAMPTZ_REHEARSAL_PARAMS="platformKafkaBootstrapServers=$BOOTSTRAP,platformKafkaSecurityProtocol=$PROTOCOL"
fi

TIMESTAMPTZ_REHEARSAL_DB=$COPY mvn -o -q test -Dtest='TimestamptzRehearsal,TenantIdRehearsal'
echo "rehearsal passed on a full copy of $SOURCE_DB"
