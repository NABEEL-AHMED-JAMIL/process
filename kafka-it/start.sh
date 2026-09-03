#!/usr/bin/env bash
# Brings up the local Kafka security stack and waits until every listener answers.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env || ! -d secrets ]]; then
  echo "Generating certificate material first..."
  ./generate-certs.sh >/dev/null
fi
set -a; source .env; set +a

docker compose -f docker-compose.kafka-it.yml up -d

echo "Waiting for the broker to accept connections on the plaintext listener..."
for i in $(seq 1 60); do
  if docker exec kafka_it kafka-broker-api-versions --bootstrap-server localhost:19092 >/dev/null 2>&1; then
    break
  fi
  sleep 2
  [[ $i -eq 60 ]] && { echo "Broker never came up. Logs:"; docker logs --tail 40 kafka_it; exit 1; }
done

# SCRAM credentials live in zookeeper, not in a JAAS file, so they can only be created once the
# cluster is running. Idempotent: re-running the stack re-asserts the same account.
echo "Creating the SCRAM account..."
docker exec kafka_it kafka-configs --zookeeper zookeeper-it:2181 \
  --alter --add-config "SCRAM-SHA-256=[password=${SASL_PASSWORD}],SCRAM-SHA-512=[password=${SASL_PASSWORD}]" \
  --entity-type users --entity-name "${SASL_USER}" >/dev/null

echo
echo "Ready. Listeners:"
printf '  %-6s %s\n' 19092 "PLAINTEXT" 19093 "SSL" 19094 "SSL + mutual TLS" \
  19095 "SASL_PLAINTEXT / PLAIN" 19096 "SASL_SSL / PLAIN" \
  19097 "SASL_PLAINTEXT / SCRAM-SHA-256" 19098 "SASL_SSL / SCRAM-SHA-512"
echo
# Not "mvn -Dtest=KafkaSecurityMatrixIT" from the host: every listener above is advertised as
# host.docker.internal so other containers can reach the broker, and the host cannot resolve that
# name. A run from here connects, is handed back an address it cannot look up, and fails with
# thirteen metadata timeouts that look like a code regression. The script runs it in a container.
echo "Run the tests with:  ../run-kafka-matrix.sh"
# No JDK flag needed: maven-toolchains-plugin pins the build to 17, which is what the image runs.
# kafka-clients 2.5 authenticates through an API removed in JDK 24, so a build that forked a newer
# JVM failed every SASL case on something that cannot happen in production.
echo "Stop with:           ./stop.sh"
