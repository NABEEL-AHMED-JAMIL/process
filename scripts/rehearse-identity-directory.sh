#!/usr/bin/env bash
# MIG-166 / MIG-153: rehearse V160-V161 (user_directory, the demoted Identity foreign keys) on a full copy of the
# live etl_job and run both audits against it, with Identity's answers read from identity_db (read-only); then drop
# the copy.
#
#   scripts/rehearse-identity-directory.sh [source-db] [identity-db]        (default etl_job identity_db)
#
# The sources are only read. Credentials come from the container's own environment and are never printed.
set -euo pipefail

SOURCE_DB=${1:-etl_job}
IDENTITY_DB=${2:-identity_db}
CONTAINER=${POSTGRES_CONTAINER:-postgres_db}
COPY="etl_job_directory_rehearsal_$(date +%s)"
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

# The changelog parameters the source was built with (V70.2's; they are part of its checksum).
if [ -z "${TIMESTAMPTZ_REHEARSAL_PARAMS:-}" ]; then
  BOOTSTRAP=$(docker exec process_app printenv SPRING_KAFKA_BOOTSTRAP_SERVERS 2>/dev/null || echo localhost:9092)
  PROTOCOL=$(docker exec process_app printenv SPRING_KAFKA_SECURITY_PROTOCOL 2>/dev/null || echo PLAINTEXT)
  export TIMESTAMPTZ_REHEARSAL_PARAMS="platformKafkaBootstrapServers=$BOOTSTRAP,platformKafkaSecurityProtocol=$PROTOCOL"
fi

DIRECTORY_REHEARSAL_DB=$COPY DIRECTORY_REHEARSAL_IDENTITY_DB=$IDENTITY_DB mvn -o -q test -Dtest='DirectoryRehearsal' \
  -DfailIfNoTests=false
echo "rehearsal passed on a full copy of $SOURCE_DB"
