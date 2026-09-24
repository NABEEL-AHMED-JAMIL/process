#!/usr/bin/env bash
# MIG-132 mutant for T1: the enqueuer's claim without FOR UPDATE SKIP LOCKED. Built from a copy of HEAD
# in a temporary directory, so the checkout itself is never edited; tagged process-two-instance:mutant-t1.
# The harness's unlocked-enqueuer mutation also drops the one-run-per-job index (V83) from its database,
# so nothing but the claim stands between two enqueuers and a job run twice.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git -C "$repo" archive HEAD | tar -x -C "$work"
query="$work/src/main/java/process/model/repository/SchedulerRepository.java"
grep -q 'limit 1 for update skip locked' "$query"
sed -i.bak 's/limit 1 for update skip locked/limit 1/' "$query"
! grep -q 'for update skip locked' "$query"
(cd "$work" && mvn -o -q package -DskipTests)
docker build -q -t process-two-instance:mutant-t1 "$work" >/dev/null
echo "built process-two-instance:mutant-t1 (claim unlocked)"
