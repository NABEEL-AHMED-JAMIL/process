#!/usr/bin/env bash
# Creates one Kafka connection profile of every security configuration for the Ajwa LLC tenant and
# tests each one against the local broker stack.
#
# Writes rows that are meant to survive, so it is off unless run by name. Credentials come from the
# running container and the generated stack, never from this repository.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"

if ! docker ps --format '{{.Names}}' | grep -qx kafka_it; then
  echo "ERROR: the broker stack is not running -- start it with kafka-it/start.sh" >&2
  exit 1
fi

CONTAINER="${PROCESS_CONTAINER:-process_app}"
if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"; then
  echo "ERROR: container '${CONTAINER}' is not running -- this run reads the database" >&2
  echo "       credentials and the encryption key from it. Start the stack first." >&2
  exit 1
fi

export E2E_DATASOURCE_USERNAME="$(docker exec "${CONTAINER}" printenv SPRING_DATASOURCE_USERNAME)"
export E2E_DATASOURCE_PASSWORD="$(docker exec "${CONTAINER}" printenv SPRING_DATASOURCE_PASSWORD)"
# The application's own key: anything written here has to be decryptable by the running app.
export E2E_LOOKUP_ENCRYPTION_KEY="$(docker exec "${CONTAINER}" printenv LOOKUP_ENCRYPTION_KEY)"

# None of the three captures above can fail on its own: the exit status of `export X=$(...)` is
# export's, not the substitution's, so a printenv that found nothing leaves an empty value and
# `set -e` never fires. Empty matters more here than in run-e2e.sh, because this driver writes
# rows that are meant to survive -- EncryptionUtil.secretKey() throws on a blank key, and the run
# would fail partway through having already committed some of them.
for required in E2E_DATASOURCE_USERNAME E2E_DATASOURCE_PASSWORD E2E_LOOKUP_ENCRYPTION_KEY; do
  if [[ -z "${!required}" ]]; then
    echo "ERROR: ${required} came back empty from container '${CONTAINER}'." >&2
    exit 1
  fi
done

# Runs inside a container on the application's own network, because that is the only place the
# names resolve: the storage connection points at host.docker.internal:9000 and the broker
# advertises host.docker.internal, neither of which the host can resolve. JDK 17 to match the
# image -- kafka-clients 2.5 cannot do SASL on JDK 24+.
NETWORK="${PROCESS_NETWORK:-process_default}"
exec docker run --rm \
  --network "${NETWORK}" \
  --add-host host.docker.internal:host-gateway \
  -v "${PWD}":/w -v "${HOME}/.m2":/root/.m2 -w /w \
  -e E2E_DATASOURCE_URL="jdbc:postgresql://postgres:5432/etl_job" \
  -e E2E_DATASOURCE_USERNAME \
  -e E2E_DATASOURCE_PASSWORD \
  -e E2E_LOOKUP_ENCRYPTION_KEY \
  maven:3.9-eclipse-temurin-17 \
  sh -c '
    # The repository pins the build to a JDK 17 at a macOS path, which does not exist in here.
    # Same version, different location, so the toolchain is restated rather than skipped.
    printf "%s" "<?xml version=\"1.0\"?><toolchains><toolchain><type>jdk</type>\
<provides><version>17</version></provides>\
<configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration></toolchain></toolchains>" > /tmp/toolchains.xml
    exec mvn -o -t /tmp/toolchains.xml test -Dtest=AjwaKafkaProvisioningDriver -DprovisionKafka=true
  ' 
