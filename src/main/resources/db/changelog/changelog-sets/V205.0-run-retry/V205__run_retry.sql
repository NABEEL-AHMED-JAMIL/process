-- MIG-345: a failed run retried from the step that failed, or from the start, as a new attempt of the same run.
--
-- - run_attempt: one row per attempt a person (the console) or an API client (POST /v1/runs/{id}/retry) asked for --
--   which attempt it is and which it follows, resume or restart, the step it starts at, the definition version it runs
--   (a resume may run a newer version whose changes are the failed step's and later ones'), and who asked. An attempt
--   the job's own retry policy made (BulkAction.scheduleRetry) has no row: it starts again from the first step, as before.
-- - run_input_file: the sha256 of each file a step read from a bucket, taken on the way while it read it, so a resume can
--   tell that a file a reused step read has changed since. One row per step execution and object; byte_count and whole
--   say how much was read (a step that stopped early hashed what it took). bucket '*' is the mark of a step that read more
--   files than are fingerprinted (process.pipeline.InputDigests.MAX_FILES): a resume cannot vouch for those.
-- - step_execution.reused_from: a step of a resumed attempt that is not run again -- its output is the earlier attempt's
--   step's, which this names. Its status is Completed; its times are null (it did not run now).
--
-- Expand-only: two new tables and one nullable column. Both tables are tenant tables, tenant_id filled from the parent
-- (V102's tenant_id_from_parent) and under row security (V181), as V183's.

-- ------------------------------------------------------------------------------------------------------- run_attempt
CREATE SEQUENCE public.run_attempt_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_attempt (
    run_attempt_id bigint DEFAULT nextval('public.run_attempt_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    attempt integer NOT NULL,
    previous_attempt integer NOT NULL,
    mode character varying(16) NOT NULL,
    from_step_index integer,
    from_step_key character varying(64),
    reused_steps integer DEFAULT 0 NOT NULL,
    pipeline_definition_id bigint,
    requested_via character varying(16) NOT NULL,
    requested_by_user_id bigint,
    requested_by_client_id character varying(128),
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_attempt PRIMARY KEY (run_attempt_id),
    CONSTRAINT ux_run_attempt_run_attempt UNIQUE (job_queue_id, attempt),
    CONSTRAINT fk_run_attempt_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_run_attempt_definition FOREIGN KEY (pipeline_definition_id)
        REFERENCES public.pipeline_definition (pipeline_definition_id),
    CONSTRAINT ck_run_attempt_attempt CHECK (attempt > 1 AND previous_attempt >= 1 AND previous_attempt < attempt),
    CONSTRAINT ck_run_attempt_mode CHECK (mode IN ('resume', 'restart')),
    CONSTRAINT ck_run_attempt_from CHECK ((mode = 'restart' AND from_step_index IS NULL) OR (mode = 'resume' AND from_step_index >= 0)),
    CONSTRAINT ck_run_attempt_reused CHECK (reused_steps >= 0),
    CONSTRAINT ck_run_attempt_via CHECK (requested_via IN ('console', 'api')),
    CONSTRAINT ck_run_attempt_who CHECK ((requested_via = 'console' AND requested_by_user_id IS NOT NULL)
        OR (requested_via = 'api' AND requested_by_client_id IS NOT NULL))
);

COMMENT ON TABLE public.run_attempt IS 'MIG-345: an attempt of a run that a person (console) or an API client asked for: resume from the failed step, or restart; who, when, from which step, on which definition version.';
COMMENT ON COLUMN public.run_attempt.mode IS 'resume: the steps before from_step_index are the previous attempt''s (step_execution.reused_from), not run again; restart: every step runs.';
COMMENT ON COLUMN public.run_attempt.pipeline_definition_id IS 'The definition version this attempt runs: for a resume, the pipeline''s latest when its changes are the failed step''s and later ones''; for a restart, the latest.';

CREATE INDEX idx_run_attempt_tenant ON public.run_attempt (tenant_id);

CREATE TRIGGER run_attempt_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_attempt
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- ---------------------------------------------------------------------------------------------------- run_input_file
CREATE SEQUENCE public.run_input_file_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_input_file (
    run_input_file_id bigint DEFAULT nextval('public.run_input_file_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    step_execution_id bigint NOT NULL,
    bucket character varying(255) NOT NULL,
    object_key character varying(1024) NOT NULL,
    byte_count bigint NOT NULL,
    sha256 character(64),
    whole boolean NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_input_file PRIMARY KEY (run_input_file_id),
    CONSTRAINT ux_run_input_file_step_object UNIQUE (step_execution_id, bucket, object_key),
    CONSTRAINT fk_run_input_file_step_tenant FOREIGN KEY (step_execution_id, tenant_id)
        REFERENCES public.step_execution (step_execution_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_input_file_bytes CHECK (byte_count >= 0),
    CONSTRAINT ck_run_input_file_sha256 CHECK ((bucket = '*' AND sha256 IS NULL) OR sha256 ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE public.run_input_file IS 'MIG-345: the sha256 of what a step read of each bucket object (byte_count bytes; whole: to its end), taken while it read it, so a resume can tell an input changed. bucket ''*'': more files than are fingerprinted.';

CREATE INDEX idx_run_input_file_tenant ON public.run_input_file (tenant_id);

CREATE TRIGGER run_input_file_tenant_id_from_parent BEFORE INSERT OR UPDATE OF step_execution_id ON public.run_input_file
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('step_execution', 'step_execution_id', 'step_execution_id');

-- ------------------------------------------------------------------------------------------------ step_execution
ALTER TABLE public.step_execution ADD COLUMN reused_from bigint;
ALTER TABLE public.step_execution ADD CONSTRAINT fk_step_execution_reused_from FOREIGN KEY (reused_from)
    REFERENCES public.step_execution (step_execution_id) ON DELETE SET NULL;
COMMENT ON COLUMN public.step_execution.reused_from IS 'MIG-345: a step of a resumed attempt that was not run again: the earlier attempt''s step whose output it is.';
CREATE INDEX idx_step_execution_reused_from ON public.step_execution (reused_from) WHERE reused_from IS NOT NULL;

-- Row security (MIG-258, V181), as V183: only where V181 has run in this database.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['run_attempt', 'run_input_file'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('GRANT USAGE, SELECT, UPDATE ON public.%I TO process_app', t || '_seq');
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
