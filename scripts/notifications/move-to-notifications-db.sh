#!/usr/bin/env bash
# Moves the notification table from etl_job into notifications_db (MIG-21).
#
#   cd process && scripts/notifications/move-to-notifications-db.sh
#
# REHEARSAL=1 SOURCE_DB=... TARGET_DB=... runs it between scratch databases without stopping anything.
#
# Stops process_app first -- nothing may write a notification while the rows move -- and leaves it
# stopped; start the new build afterwards with `docker compose up -d --no-deps process_app`.
#
# What it does, in order, stopping at the first failure:
#   1. creates notifications_db if it is missing;
#   2. refuses to run if notifications_db.notification already holds rows;
#   3. builds the table from db/notifications/sql/V1.0-notification.sql (the changelog's own SQL)
#      unless it is there already;
#   4. copies every row, keeping its id; a row with no tenant was a platform admin's and is filed
#      under the platform scope, 0;
#   5. sets the id sequence so the next insert gets max(id)+1, and checks it;
#   6. compares row counts and an md5 of every business column, tenant by tenant;
#   7. renames etl_job.notification to notification_moved_mig21, so anything still writing to the
#      old table fails loudly instead of writing where no one reads. Drop it once you are content.
set -euo pipefail

cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
PG_CONTAINER=${PG_CONTAINER:-postgres_db}
SOURCE_DB=${SOURCE_DB:-etl_job}
TARGET_DB=${TARGET_DB:-notifications_db}
SQL=src/main/resources/db/notifications/sql/V1.0-notification.sql

psql_in() {
  local db=$1; shift
  docker exec -i -e PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" "$PG_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -X -q -U "$SPRING_DATASOURCE_USERNAME" -d "$db" "$@"
}
value() { psql_in "$1" -tA -c "$2"; }

if [ "${REHEARSAL:-0}" = "1" ]; then
  # SOURCE_DB and TARGET_DB point at scratch copies; the live app is not involved.
  echo "== rehearsal: $SOURCE_DB -> $TARGET_DB, process_app left running"
else
  echo "== stopping process_app, so nothing writes a notification during the move"
  docker compose stop process_app >/dev/null
fi

if [ "$(value "$SOURCE_DB" "SELECT to_regclass('public.notification') IS NOT NULL")" != "t" ]; then
  echo "!! $SOURCE_DB has no notification table -- already moved?"; exit 1
fi

if [ "$(value postgres "SELECT count(*) FROM pg_database WHERE datname = '$TARGET_DB'")" = "0" ]; then
  echo "== creating $TARGET_DB"
  psql_in postgres -c "CREATE DATABASE $TARGET_DB"
fi

if [ "$(value "$TARGET_DB" "SELECT to_regclass('public.notification') IS NOT NULL")" = "t" ]; then
  if [ "$(value "$TARGET_DB" "SELECT count(*) FROM notification")" != "0" ]; then
    echo "!! $TARGET_DB.notification already holds rows; refusing to copy over them"; exit 1
  fi
else
  echo "== building $TARGET_DB.notification from $SQL"
  psql_in "$TARGET_DB" < "$SQL"
fi

COLUMNS="notification_id, tenant_id, recipient_user_id, type, severity, title, message, link_url, is_read, read_at, date_created"
echo "== copying rows"
psql_in "$SOURCE_DB" -c "\copy (SELECT notification_id, coalesce(tenant_id, 0), recipient_user_id, type, severity, title, message, link_url, is_read, read_at, date_created FROM notification ORDER BY notification_id) TO STDOUT" \
  | psql_in "$TARGET_DB" -c "\copy notification ($COLUMNS) FROM STDIN"

echo "== setting the id sequence"
MAX_ID=$(value "$TARGET_DB" "SELECT coalesce(max(notification_id), 0) FROM notification")
if [ "$MAX_ID" = "0" ]; then
  value "$TARGET_DB" "SELECT setval(pg_get_serial_sequence('notification', 'notification_id'), 1, false)" >/dev/null
else
  value "$TARGET_DB" "SELECT setval(pg_get_serial_sequence('notification', 'notification_id'), $MAX_ID, true)" >/dev/null
fi
NEXT=$(value "$TARGET_DB" "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM notification_notification_id_seq")
if [ "$NEXT" != "$((MAX_ID + 1))" ]; then
  echo "!! the next id would be $NEXT, not $((MAX_ID + 1))"; exit 1
fi
echo "   max(id) = $MAX_ID, next id = $NEXT"

echo "== comparing, tenant by tenant"
CHECK="SELECT tenant_id, count(*), md5(string_agg(concat_ws('|', notification_id, recipient_user_id, type, severity, title, message, link_url, is_read, read_at, date_created), E'\n' ORDER BY notification_id)) FROM"
BEFORE=$(value "$SOURCE_DB" "$CHECK (SELECT coalesce(tenant_id, 0) AS tenant_id, notification_id, recipient_user_id, type, severity, title, message, link_url, is_read, read_at, date_created FROM notification) n GROUP BY 1 ORDER BY 1")
AFTER=$(value "$TARGET_DB" "$CHECK notification GROUP BY 1 ORDER BY 1")
if [ "$BEFORE" != "$AFTER" ]; then
  echo "!! the copy does not match:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1
fi
echo "$AFTER" | awk -F'|' '{ printf "   tenant %-6s %5s rows  %s\n", $1, $2, $3 }'
echo "   total $(value "$TARGET_DB" "SELECT count(*) FROM notification") rows -- identical"

echo "== retiring $SOURCE_DB.notification"
psql_in "$SOURCE_DB" -c "ALTER TABLE notification RENAME TO notification_moved_mig21"

echo "== done. Start the new build: docker compose up -d --no-deps process_app"
