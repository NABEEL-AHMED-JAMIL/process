-- Owner decision 2026-10-01 ("back up, check, then drop"): drop the read-only copies left in etl_job when their data moved
-- to each service's own database. Unlike V195, a table holding rows is dropped too: the backup is the safety net.
--
--   ai_model_connection, ai_prompt, ai_prompt_version, ai_prompt_run          -- ai_db (MIG-147/150, V63)
--   analytics_query, analytics_query_run, analytics_analysis,
--   analytics_dashboard, analytics_dashboard_widget                           -- analytics_db (MIG-128, V62)
--   billing_account, billing_document, billing_number_counter, invoice_line   -- billing_db (MIG-88/89, V61)
--   notification_moved_mig21                                                  -- notifications_db (MIG-21, a clean slate by design)
--   document_converter_task_moved_mig41                                       -- media_db (MIG-41, V54)
--
-- Backup: before this ran, each table was dumped (schema and data) on the live instance with
--   pg_dump -d etl_job --table=public.<table>  ->  etl-platform/backups/2026-10-01-etl_job-<table>.sql
-- and each file's COPY row count checked against the table. To bring one back, psql -d etl_job -f that file (after
-- this changeset's rollback, drop the empty table first, or load only its COPY block).
--
-- Checked row by row against the live tables first (2026-10-01). Every etl_job row of ai_*, analytics_*, billing_account
-- and document_converter_task is in its service's table, by primary key; where values differ the service's row is the
-- newer one (keys re-sealed under AI's key, prompts deleted since, widgets edited since, counters advanced). What was NOT
-- in the destination, and was not copied:
--   notification_moved_mig21  1933 rows, 2026-09-16 .. 2026-09-23, all older than notifications_db: MIG-21 was a clean slate.
--   invoice_line              15 rows of invoice 73 (INV-2026-09-0001, a draft of 2026-09-23 for workspace 2901): the
--                             invoice itself never reached billing_db, and workspace 2901 is deleted.
--   billing_document          5 statements of workspace 2901 (2026-09-23, before billing_db existed); billing_db has since
--                             issued its own STM-2901-2026-01-01-2026-12-31-1, so the old numbers would also collide.
--   billing_number_counter    INV-2026-09 (the counter of that same unmoved invoice).
-- Nothing was copied anywhere.
--
-- Dropped children first, so no foreign key needs CASCADE; a dependency nobody knew about fails the changeset instead of
-- silently taking something else with it. invoice (it still holds its one row) stays: invoice_line's keys onto it go with
-- invoice_line. With the tables go their sequences: the V50 baseline's ai_*_seq / analytics_*_seq and
-- document_converter_task_id_seq (owned by nothing) explicitly, and the ones a column owns
-- (billing_account_*, billing_document_*, invoice_line_*, notification_notification_id_seq) with their tables.
-- A table or sequence that is absent is skipped: a database built from the changelog never had the two *_moved_mig* copies.
--
-- Rollback: V196__rollback.sql recreates every table that is missing, EMPTY, with its columns, keys, indexes, comments,
-- read-only trigger, row security policy, grants and sequences as they stood on etl_job on 2026-10-01 (pg_dump -s).

DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    PERFORM set_config('row_security', 'off', true);
    FOREACH t IN ARRAY ARRAY['ai_prompt_run', 'ai_prompt_version', 'ai_prompt', 'ai_model_connection',
                             'analytics_dashboard_widget', 'analytics_query_run', 'analytics_dashboard', 'analytics_analysis',
                             'analytics_query', 'invoice_line', 'billing_document', 'billing_account', 'billing_number_counter',
                             'notification_moved_mig21', 'document_converter_task_moved_mig41'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        EXECUTE format('DROP TABLE public.%I', t);
        RAISE NOTICE 'V196: dropped public.% (% rows)', t, n;
    END LOOP;
    FOREACH t IN ARRAY ARRAY['ai_model_connection_seq', 'ai_prompt_seq', 'ai_prompt_run_seq', 'analytics_analysis_seq',
                             'analytics_dashboard_seq', 'analytics_dashboard_widget_seq', 'analytics_query_seq',
                             'analytics_query_run_seq', 'document_converter_task_id_seq'] LOOP
        EXECUTE format('DROP SEQUENCE IF EXISTS public.%I', t);
    END LOOP;
END
$$;
