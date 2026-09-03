#!/usr/bin/env bash
# Runs KafkaSecurityMatrixIT against the local broker: every listener it publishes, each with the
# store types and protocols a real profile would carry.
#
# Inside a container, because that is the only place the broker's own addresses resolve. Every
# listener in docker-compose.kafka-it.yml advertises host.docker.internal so that other containers
# can reach it, and the host cannot resolve that name -- a client on the host connects to
# localhost:1909x, is handed back "host.docker.internal:1909x" as the node to talk to, and then
# sits there until the call times out. The failure looks nothing like the cause: thirteen
# TimeoutExceptions saying "Timed out waiting for a node assignment", no handshake error, no
# authentication error, and the four cases that never open a connection still passing. Worth
# knowing before reading that as a regression.
#
# JDK 17 to match what the application image runs. kafka-clients 2.5 authenticates through
# Subject.getSubject, which was removed with the SecurityManager in JDK 24, so a build that forked
# a newer JVM fails every SASL case on something that cannot happen in production.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"

if ! docker ps --format '{{.Names}}' | grep -qx kafka_it; then
  echo "ERROR: the test broker is not running. Start it with:" >&2
  echo "       ./kafka-it/start.sh" >&2
  exit 1
fi

NETWORK="${KAFKA_IT_NETWORK:-$(docker inspect kafka_it \
  --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{break}}{{end}}')}"

echo "Running the Kafka security matrix on network ${NETWORK}"
exec docker run --rm \
  --network "${NETWORK}" \
  --add-host host.docker.internal:host-gateway \
  -v "${PWD}":/w -v "${HOME}/.m2":/root/.m2 -w /w \
  maven:3.9-eclipse-temurin-17 \
  sh -c '
    # The repository pins the build to a JDK 17 at a macOS path, which does not exist in here.
    # Same version, different location, so the toolchain is restated rather than skipped.
    printf "%s" "<?xml version=\"1.0\"?><toolchains><toolchain><type>jdk</type>\
<provides><version>17</version></provides>\
<configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration></toolchain></toolchains>" > /tmp/toolchains.xml
    # The word after the script is $0, not $1 -- `sh -c script a b` leaves $1 as "b". Naming it
    # is what puts the arguments given to this script where the "$@" below can reach them;
    # without that name the first argument was consumed as $0 and all of them silently dropped.
    exec mvn -o -t /tmp/toolchains.xml test -Dtest=KafkaSecurityMatrixIT \
      -Dkafka.it.host=host.docker.internal "$@"
  ' run-kafka-matrix "$@"
