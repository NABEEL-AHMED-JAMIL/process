#!/usr/bin/env bash
# Tears the stack down. Secrets are left in place so a re-run does not have to reissue them;
# delete secrets/ and .env to start completely fresh.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"
docker compose -f docker-compose.kafka-it.yml down -v
