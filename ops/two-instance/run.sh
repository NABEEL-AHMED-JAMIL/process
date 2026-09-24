#!/usr/bin/env bash
# MIG-132: the two-instance harness, one command. See README.md.
#
#   ops/two-instance/run.sh                      build process, then every check (T1-T10) and the unit gate
#   ops/two-instance/run.sh --only T1,T4         some checks
#   ops/two-instance/run.sh --mutate split-redis prove a check goes red when its mechanism is broken
#   ops/two-instance/run.sh --no-build ...       reuse the process-two-instance:harness image as it is
#
# Exit status 0 only when every selected check passed (or, with --mutate, when the expected ones failed).
set -euo pipefail
cd "$(dirname "$0")/../.."

build=1
args=()
for a in "$@"; do
  if [[ "$a" == "--no-build" ]]; then build=0; else args+=("$a"); fi
done

if (( build )); then
  echo "[build] mvn -o package -DskipTests (the unit gate below runs the suites that matter here)"
  mvn -o -q package -DskipTests
  echo "[build] docker build -t ${PROCESS_IMAGE:-process-two-instance:harness}"
  # Its own tag: never process-process_app, the live container's image.
  docker build -q -t "${PROCESS_IMAGE:-process-two-instance:harness}" . >/dev/null
fi

exec python3 ops/two-instance/harness.py ${args[@]+"${args[@]}"}
