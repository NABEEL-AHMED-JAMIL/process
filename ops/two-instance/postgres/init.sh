#!/usr/bin/env bash
# MIG-132 harness Postgres, first start only (its data directory is tmpfs, so that is every run).
# etl_job comes from POSTGRES_DB and is built by process's own Liquibase when A and B boot. The other
# services' databases and roles mirror their create-*-db.sh scripts, with this run's passwords: the
# grants themselves are those services' changesets, as in production.
set -euo pipefail

psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname postgres \
  -v ao="$ANALYTICS_OWNER_PASSWORD" -v aa="$ANALYTICS_APP_PASSWORD" \
  -v bo="$BILLING_OWNER_PASSWORD" -v ba="$BILLING_APP_PASSWORD" <<'SQL'
CREATE ROLE analytics_owner LOGIN PASSWORD :'ao';
CREATE ROLE analytics_app LOGIN PASSWORD :'aa';
CREATE ROLE billing_owner LOGIN PASSWORD :'bo';
CREATE ROLE billing_app LOGIN PASSWORD :'ba';
CREATE ROLE meter_app NOLOGIN;
CREATE DATABASE notifications_db;
CREATE DATABASE analytics_db OWNER analytics_owner;
CREATE DATABASE billing_db OWNER billing_owner;
SQL

for db in analytics billing; do
  psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname "${db}_db" <<SQL
ALTER SCHEMA public OWNER TO ${db}_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE ${db}_db FROM PUBLIC;
GRANT CONNECT ON DATABASE ${db}_db TO ${db}_owner, ${db}_app;
SQL
done
psql -q --username "$POSTGRES_USER" --dbname billing_db -c "GRANT CONNECT ON DATABASE billing_db TO meter_app"
echo "mig132: databases and roles ready"
