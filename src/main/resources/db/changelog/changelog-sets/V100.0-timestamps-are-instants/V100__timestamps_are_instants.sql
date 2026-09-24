-- V100 (MIG-28, MIG-96, MIG-163; ADR-002): every naive timestamp process owns becomes timestamptz.
--
-- Each stored value is America/Chicago wall-clock -- the JVM that wrote it was told it lived in Chicago
-- (ModelApplication.main's TimeZone.setDefault) -- so it is converted USING col AT TIME ZONE 'America/Chicago':
-- 2026-01-15 08:00:00 becomes 2026-01-15 14:00:00+00, the instant it always meant, not 08:00:00+00, which a
-- plain cast would make of it in this UTC database. The DST edges resolve as the old JVM read them:
--   2026-03-08 02:30 (does not exist in Chicago) -> 08:30Z, i.e. 03:30 CDT;
--   2026-11-01 01:30 (happens twice)             -> 07:30Z, the second one (CST).
--
-- Not converted, and why: see TimestampColumns.LEFT_NAIVE -- the read-only retention copies of tables other
-- services now own (V57, V61, V62, V63), and Liquibase's own two tables. identity_signing_key is identity-prep's
-- (V68) and is converted wherever it exists.
--
-- A nonexistent local time (the 02:30 above) cannot come back from its instant: 08:30Z reads as 03:30. So the
-- few such values are kept, keyed by their row, in timestamptz_v100_unrepresentable, and V100's rollback puts
-- them back -- the down-migration restores every stored string byte for byte.
--
-- The Dashboard's day index was on date(date_created), which is not immutable on a timestamptz (the day depends
-- on the session's zone) and so cannot index one. It is rebuilt on the Chicago day, the expression the
-- Dashboard's queries now use.

CREATE TABLE public.timestamptz_v100_unrepresentable (
    table_name  text NOT NULL,
    column_name text NOT NULL,
    row_key     text NOT NULL,
    original    timestamp without time zone NOT NULL,
    PRIMARY KEY (table_name, column_name, row_key)
);

COMMENT ON TABLE public.timestamptz_v100_unrepresentable IS
    'V100: stored values that were not a real America/Chicago time (the spring-forward gap), kept so that V100''s rollback restores them exactly. Dropped by that rollback.';

DO $$
DECLARE
    spec     text[];
    tbl      text;
    col      text;
    key_expr text;
    new_type text;
    alters   text[];
BEGIN
    DROP INDEX IF EXISTS public.idx_job_queue_date_created_day;

    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ARRAY['app_user',                     'date_created,last_login_at'],
        ARRAY['job_audit_logs',               'date_created'],
        ARRAY['job_queue',                    'callback_token_expires_at,date_created,end_time,next_attempt_at,prepare_lease_until,prepared_at,refused_callback_at,skip_time,start_time'],
        ARRAY['kafka_connection_profile',     'date_created,last_tested_at'],
        ARRAY['lookup_data',                  'date_created'],
        ARRAY['page_access_profile',          'date_created,date_updated'],
        ARRAY['pipeline',                     'date_created'],
        ARRAY['scheduler',                    'date_created,date_updated,next_run_at'],
        ARRAY['shedlock',                     'lock_until,locked_at'],
        ARRAY['source_job',                   'date_created,last_job_run'],
        ARRAY['tenant',                       'date_created'],
        ARRAY['tenant_request',               'date_created,decided_at'],
        ARRAY['tenant_task_type_kafka_route', 'date_created'],
        ARRAY['user_page_access',             'date_created'],
        ARRAY['worker_callback_receipt',      'received_at'],
        ARRAY['identity_signing_key',         'created_at,retired_at']
    ] LOOP
        tbl := spec[1];
        IF to_regclass('public.' || tbl) IS NULL THEN
            CONTINUE;
        END IF;
        SELECT string_agg(format('%I::text', a.attname), ' || ''|'' || ' ORDER BY k.ord) INTO key_expr
          FROM pg_index i
         CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
         WHERE i.indrelid = ('public.' || tbl)::regclass AND i.indisprimary;
        alters := ARRAY[]::text[];
        FOREACH col IN ARRAY string_to_array(spec[2], ',') LOOP
            -- The declared precision is kept: shedlock's are timestamp(3).
            SELECT replace(format_type(a.atttypid, a.atttypmod), 'without time zone', 'with time zone') INTO new_type
              FROM pg_attribute a
             WHERE a.attrelid = ('public.' || tbl)::regclass AND a.attname = col AND NOT a.attisdropped
               AND a.atttypid = 'timestamp'::regtype;
            IF new_type IS NULL THEN
                CONTINUE;
            END IF;
            -- key_expr is NULL only for a table with no primary key; the insert then fails on row_key's NOT NULL,
            -- and only if that table actually holds a value in the gap.
            EXECUTE format('INSERT INTO public.timestamptz_v100_unrepresentable (table_name, column_name, row_key, original) '
                || 'SELECT %L, %L, %s, %I FROM public.%I '
                || 'WHERE %I IS NOT NULL AND (%I AT TIME ZONE ''America/Chicago'') AT TIME ZONE ''America/Chicago'' <> %I',
                tbl, col, key_expr, col, tbl, col, col, col);
            alters := alters || format('ALTER COLUMN %I TYPE %s USING %I AT TIME ZONE ''America/Chicago''', col, new_type, col);
        END LOOP;
        IF array_length(alters, 1) > 0 THEN
            EXECUTE format('ALTER TABLE public.%I %s', tbl, array_to_string(alters, ', '));
        END IF;
    END LOOP;

    IF to_regclass('public.job_queue') IS NOT NULL THEN
        CREATE INDEX idx_job_queue_date_created_day
            ON public.job_queue (((date_created AT TIME ZONE 'America/Chicago')::date));
    END IF;
END $$;
