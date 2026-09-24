-- Every enum-backed status column accepts only its Java enum's exact spellings (MIG-166 follow-up, task 7).
--
-- Hibernate maps these columns with @Enumerated(EnumType.STRING): Enum.valueOf on read, so one row spelt 'ACTIVE'
-- where the enum says 'Active' makes every read of that row throw ("No enum constant process.model.enums.Status.
-- ACTIVE") -- 50 source_job rows written straight into the database by old e2e seed scripts made their jobs
-- impossible even to delete through the API (500). The spellings are the enums' (process.model.enums):
--   Status        Inactive, Active, Delete
--   JobStatus     Queue, Start, Running, Failed, Completed, Skip, Interrupt, Missed
--   Execution     Auto, Manual
-- Not here: app_user, tenant and page_access_profile. Their rows live in identity_db since MIG-107, and Core's
-- copies are read-only (identity_moved_read_only refuses every write), so nothing can put a new spelling in them;
-- identity_db's own columns are Identity's to constrain.
-- EnumStatusSpellingsPostgresTest holds this list to the entities: an @Enumerated column without its CHECK, or a
-- CHECK whose values differ from the enum, fails it.
--
-- For each column: first the repair -- a value that matches an enum spelling but for case is respelt (all data here
-- is test data; each count is reported as a NOTICE); a value that matches no spelling at all stops the migration
-- by name, since there is nothing safe to turn it into -- then the CHECK. A table or column this database does not
-- have is skipped with a NOTICE. Re-runnable: the CHECK is dropped and re-made. Rehearsed on a copy of live etl_job
-- on 2026-09-24: source_job.job_status 50 rows respelt (49 ACTIVE, 1 INACTIVE), nothing else.
DO $$
DECLARE
    spec record;
    fixed bigint;
    bad text;
    ck text;
BEGIN
    FOR spec IN SELECT * FROM (VALUES
        ('source_job',               'job_status',         ARRAY['Inactive','Active','Delete']),
        ('source_job',               'job_running_status', ARRAY['Queue','Start','Running','Failed','Completed','Skip','Interrupt','Missed']),
        ('source_job',               'execution',          ARRAY['Auto','Manual']),
        ('source_task',              'task_status',        ARRAY['Inactive','Active','Delete']),
        ('source_task_type',         'task_type_status',   ARRAY['Inactive','Active','Delete']),
        ('job_queue',                'job_status',         ARRAY['Queue','Start','Running','Failed','Completed','Skip','Interrupt','Missed']),
        ('job_queue',                'status',             ARRAY['Inactive','Active','Delete']),
        ('job_audit_logs',           'status',             ARRAY['Inactive','Active','Delete']),
        ('pipeline',                 'status',             ARRAY['Inactive','Active','Delete']),
        ('kafka_connection_profile', 'status',             ARRAY['Inactive','Active','Delete'])
    ) AS s(tbl, col, allowed) LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name = spec.tbl AND column_name = spec.col) THEN
            RAISE NOTICE 'V164: %.% is not in this database; skipped.', spec.tbl, spec.col;
            CONTINUE;
        END IF;
        EXECUTE format('UPDATE %1$I SET %2$I = a.canon FROM unnest($1::text[]) AS a(canon) '
            || 'WHERE lower(%1$I.%2$I) = lower(a.canon) AND %1$I.%2$I <> a.canon', spec.tbl, spec.col) USING spec.allowed;
        GET DIAGNOSTICS fixed = ROW_COUNT;
        RAISE NOTICE 'V164: %.%: % row(s) respelt to the enum''s exact spelling.', spec.tbl, spec.col, fixed;
        EXECUTE format('SELECT string_agg(DISTINCT %2$I, '', '') FROM %1$I WHERE %2$I IS NOT NULL AND NOT (%2$I = ANY ($1::text[]))',
            spec.tbl, spec.col) INTO bad USING spec.allowed;
        IF bad IS NOT NULL THEN
            RAISE EXCEPTION 'V164: %.% holds value(s) that match no spelling of its enum (%): %', spec.tbl, spec.col,
                array_to_string(spec.allowed, ', '), bad;
        END IF;
        ck := 'ck_' || spec.tbl || '_' || spec.col || '_enum';
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', spec.tbl, ck);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IN (%s))', spec.tbl, ck, spec.col,
            (SELECT string_agg(quote_literal(v), ', ') FROM unnest(spec.allowed) AS v));
    END LOOP;
END
$$;
