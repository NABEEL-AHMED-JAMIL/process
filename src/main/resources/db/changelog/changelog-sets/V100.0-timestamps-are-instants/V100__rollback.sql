-- V100's way back: every converted column to naive America/Chicago wall-clock again, the spring-forward values
-- V100 kept put back exactly, the Dashboard's day index on date(date_created) as V70.1 made it.
--
-- A kept value is restored only where the row still holds what V100 made of it: a row the application wrote
-- to since keeps what it wrote.

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
        ARRAY['job_audit_logs',               'date_created'],
        ARRAY['job_queue',                    'callback_token_expires_at,date_created,end_time,next_attempt_at,prepare_lease_until,prepared_at,refused_callback_at,skip_time,start_time'],
        ARRAY['kafka_connection_profile',     'date_created,last_tested_at'],
        ARRAY['lookup_data',                  'date_created'],
        ARRAY['pipeline',                     'date_created'],
        ARRAY['scheduler',                    'date_created,date_updated,next_run_at'],
        ARRAY['shedlock',                     'lock_until,locked_at'],
        ARRAY['source_job',                   'date_created,last_job_run'],
        ARRAY['tenant_task_type_kafka_route', 'date_created'],
        ARRAY['worker_callback_receipt',      'received_at']
    ] LOOP
        tbl := spec[1];
        IF to_regclass('public.' || tbl) IS NULL THEN
            CONTINUE;
        END IF;
        alters := ARRAY[]::text[];
        FOREACH col IN ARRAY string_to_array(spec[2], ',') LOOP
            SELECT replace(format_type(a.atttypid, a.atttypmod), 'with time zone', 'without time zone') INTO new_type
              FROM pg_attribute a
             WHERE a.attrelid = ('public.' || tbl)::regclass AND a.attname = col AND NOT a.attisdropped
               AND a.atttypid = 'timestamptz'::regtype;
            IF new_type IS NOT NULL THEN
                alters := alters || format('ALTER COLUMN %I TYPE %s USING %I AT TIME ZONE ''America/Chicago''', col, new_type, col);
            END IF;
        END LOOP;
        IF array_length(alters, 1) > 0 THEN
            EXECUTE format('ALTER TABLE public.%I %s', tbl, array_to_string(alters, ', '));
        END IF;
        IF to_regclass('public.timestamptz_v100_unrepresentable') IS NOT NULL THEN
            SELECT string_agg(format('t.%I::text', a.attname), ' || ''|'' || ' ORDER BY k.ord) INTO key_expr
              FROM pg_index i
             CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
             WHERE i.indrelid = ('public.' || tbl)::regclass AND i.indisprimary;
            FOREACH col IN ARRAY string_to_array(spec[2], ',') LOOP
                IF key_expr IS NOT NULL AND EXISTS (SELECT 1 FROM public.timestamptz_v100_unrepresentable u
                                                     WHERE u.table_name = tbl AND u.column_name = col) THEN
                    EXECUTE format('UPDATE public.%I t SET %I = u.original FROM public.timestamptz_v100_unrepresentable u '
                        || 'WHERE u.table_name = %L AND u.column_name = %L AND u.row_key = %s '
                        || 'AND t.%I = (u.original AT TIME ZONE ''America/Chicago'') AT TIME ZONE ''America/Chicago''',
                        tbl, col, tbl, col, key_expr, col);
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    IF to_regclass('public.job_queue') IS NOT NULL THEN
        CREATE INDEX idx_job_queue_date_created_day ON public.job_queue ((date(date_created)));
    END IF;
END $$;

DROP TABLE IF EXISTS public.timestamptz_v100_unrepresentable;
