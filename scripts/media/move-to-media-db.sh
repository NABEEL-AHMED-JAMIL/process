#!/usr/bin/env bash
# Moves document_converter_task from etl_job into media_db (MIG-41).
#
#   cd process && scripts/media/move-to-media-db.sh
#   REHEARSAL=1 SOURCE_DB=... TARGET_DB=... runs it between scratch databases without stopping anything.
#
# Stops process_app first -- nothing may write a conversion while the rows move -- and leaves it
# stopped; start the new build afterwards with `docker compose up -d --no-deps process_app`.
#
# In order, stopping at the first failure:
#   1. creates media_db if it is missing;
#   2. refuses to run if media_db.document_converter_task already holds rows;
#   3. builds the table from db/media/sql/V1.0-document_converter_task.sql (the changelog's own SQL)
#      unless it is there already;
#   4. copies every row, keeping its id; a row with no tenant (a platform admin's) is filed under the
#      platform scope, 0;
#   5. sets document_converter_task_id_seq so the next insert gets max(id)+1 -- never below its start
#      of 1000 -- and checks it;
#   6. compares row counts and an md5 of every business column, tenant by tenant;
#   7. renames etl_job.document_converter_task to document_converter_task_moved_mig41, so anything
#      still writing to the old table fails loudly instead of writing where no one reads.
set -euo pipefail

cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
PG_CONTAINER=${PG_CONTAINER:-postgres_db}
SOURCE_DB=${SOURCE_DB:-etl_job}
TARGET_DB=${TARGET_DB:-media_db}
SQL=src/main/resources/db/media/sql/V1.0-document_converter_task.sql
TABLE=document_converter_task

psql_in() {
  local db=$1; shift
  docker exec -i -e PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" "$PG_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -X -q -U "$SPRING_DATASOURCE_USERNAME" -d "$db" "$@"
}
value() { psql_in "$1" -tA -c "$2"; }

if [ "${REHEARSAL:-0}" = "1" ]; then
  echo "== rehearsal: $SOURCE_DB -> $TARGET_DB, process_app left running"
else
  echo "== stopping process_app, so nothing writes a conversion during the move"
  docker compose stop process_app >/dev/null
fi

if [ "$(value "$SOURCE_DB" "SELECT to_regclass('public.$TABLE') IS NOT NULL")" != "t" ]; then
  echo "!! $SOURCE_DB has no $TABLE table -- already moved?"; exit 1
fi
if [ "$(value postgres "SELECT count(*) FROM pg_database WHERE datname = '$TARGET_DB'")" = "0" ]; then
  echo "== creating $TARGET_DB"
  psql_in postgres -c "CREATE DATABASE $TARGET_DB"
fi
if [ "$(value "$TARGET_DB" "SELECT to_regclass('public.$TABLE') IS NOT NULL")" = "t" ]; then
  if [ "$(value "$TARGET_DB" "SELECT count(*) FROM $TABLE")" != "0" ]; then
    echo "!! $TARGET_DB.$TABLE already holds rows; refusing to copy over them"; exit 1
  fi
else
  echo "== building $TARGET_DB.$TABLE from $SQL"
  psql_in "$TARGET_DB" < "$SQL"
fi

BUSINESS="task_name, input_file_name, input_format, input_content_type, input_file_size, output_format, output_file_name, output_content_type, output_file_size, bucket_name, target_folder, input_storage_key, output_storage_key, status, date_created"
echo "== copying rows"
psql_in "$SOURCE_DB" -c "\copy (SELECT document_converter_task_id, coalesce(tenant_id, 0), $BUSINESS FROM $TABLE ORDER BY document_converter_task_id) TO STDOUT" \
  | psql_in "$TARGET_DB" -c "\copy $TABLE (document_converter_task_id, tenant_id, $BUSINESS) FROM STDIN"

echo "== setting the id sequence"
MAX_ID=$(value "$TARGET_DB" "SELECT coalesce(max(document_converter_task_id), 0) FROM $TABLE")
if [ "$MAX_ID" -lt 1000 ]; then
  value "$TARGET_DB" "SELECT setval('document_converter_task_id_seq', 1000, false)" >/dev/null
  EXPECTED=1000
else
  value "$TARGET_DB" "SELECT setval('document_converter_task_id_seq', $MAX_ID, true)" >/dev/null
  EXPECTED=$((MAX_ID + 1))
fi
NEXT=$(value "$TARGET_DB" "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM document_converter_task_id_seq")
if [ "$NEXT" != "$EXPECTED" ]; then
  echo "!! the next id would be $NEXT, not $EXPECTED"; exit 1
fi
echo "   max(id) = $MAX_ID, next id = $NEXT"

echo "== comparing, tenant by tenant"
CHECK="SELECT tenant_id, count(*), md5(string_agg(concat_ws('|', document_converter_task_id, $BUSINESS), E'\n' ORDER BY document_converter_task_id)) FROM"
BEFORE=$(value "$SOURCE_DB" "$CHECK (SELECT coalesce(tenant_id, 0) AS tenant_id, document_converter_task_id, $BUSINESS FROM $TABLE) t GROUP BY 1 ORDER BY 1")
AFTER=$(value "$TARGET_DB" "$CHECK $TABLE GROUP BY 1 ORDER BY 1")
if [ "$BEFORE" != "$AFTER" ]; then
  echo "!! the copy does not match:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1
fi
echo "$AFTER" | awk -F'|' '{ printf "   tenant %-6s %5s rows  %s\n", $1, $2, $3 }'
echo "   total $(value "$TARGET_DB" "SELECT count(*) FROM $TABLE") rows -- identical"

echo "== retiring $SOURCE_DB.$TABLE"
psql_in "$SOURCE_DB" -c "ALTER TABLE $TABLE RENAME TO ${TABLE}_moved_mig41"

echo "== done. Start the new build: docker compose up -d --no-deps process_app"
