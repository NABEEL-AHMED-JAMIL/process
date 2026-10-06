-- Wave 4 (MIG-242, Core's part): the AI model a run's steps run on. ai-service decides and checks the model (its
-- ModelChoice, ai_db V4.1); Core says which one a run asks for, and keeps what each step ran on.
--
-- - source_job.model_profiles: the schedule's setting -- per AI step of the job's pipeline, the ai-service model option
--   (ai_step_model_option.model_option_id) its runs ask for. A JSON object {"<step tag>": "<option id>"}; a step not
--   named runs on its step's default. Core writes it through its own endpoint only (JPA maps it read-only), so no
--   job edit can overwrite it.
-- - job_queue.model_profiles: one run's "Run with..." -- the same shape, set when a person runs a job by hand, and
--   winning over the schedule's for the steps it names. A retry is the same row, so it keeps the choice.
-- - run_ai_step: one AI step of one attempt of a run -- what Core asked for (the profile and where it came from) and,
--   for a server step, what ai-service answered it ran on: model, connection, option, how it was chosen, prompt and
--   prompt version. The run's manifest reads it; a worker step's row says what the worker was told to ask for.
-- - result_record: the model, the option and how it was chosen, beside V180's connection and prompt version, and the
--   AI step whose answer the result is.
--
-- Expand-only: two nullable columns, one new table, four nullable columns; nothing existing is read differently. The
-- rules of process's tables as V180: tenant_id NOT NULL held to the parent's (V102's tenant_id_from_parent), instants,
-- sequences from 1000, other services' ids (option, connection, prompt) plain bigints.

-- ---------------------------------------------------------------------------------- the schedule's and the run's
ALTER TABLE public.source_job ADD COLUMN model_profiles text;
ALTER TABLE public.source_job ADD CONSTRAINT ck_source_job_model_profiles_object
    CHECK (model_profiles IS NULL OR json_typeof(model_profiles::json) = 'object');
COMMENT ON COLUMN public.source_job.model_profiles IS 'The schedule''s AI model per step: {"<step tag>": "<ai-service model option id>"}; a step not named runs on its default. Wave 4.';

ALTER TABLE public.job_queue ADD COLUMN model_profiles text;
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_model_profiles_object
    CHECK (model_profiles IS NULL OR json_typeof(model_profiles::json) = 'object');
COMMENT ON COLUMN public.job_queue.model_profiles IS 'This run''s "Run with...": {"<step tag>": "<ai-service model option id>"}, over the schedule''s for the steps it names. Wave 4.';

-- -------------------------------------------------------------------------------------------------- run_ai_step
CREATE SEQUENCE public.run_ai_step_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_ai_step (
    run_ai_step_id bigint DEFAULT nextval('public.run_ai_step_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    step_key character varying(128) NOT NULL,
    run_in character varying(16) NOT NULL,
    prompt_id bigint,
    prompt_version integer,
    model_profile character varying(64),
    profile_source character varying(16),
    outcome character varying(16) NOT NULL,
    model character varying(255),
    connection_id bigint,
    model_option_id bigint,
    model_choice character varying(16),
    reused boolean DEFAULT false NOT NULL,
    error text,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_ai_step PRIMARY KEY (run_ai_step_id),
    -- One row per AI step per attempt: a retried run records its steps again, and a re-prepared attempt overwrites.
    CONSTRAINT ux_run_ai_step_run_step UNIQUE (job_queue_id, attempt, step_key),
    -- Part of its run: in the run's workspace, and gone with the run.
    CONSTRAINT fk_run_ai_step_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_ai_step_attempt CHECK (attempt > 0),
    CONSTRAINT ck_run_ai_step_run_in CHECK (run_in IN ('server', 'worker')),
    CONSTRAINT ck_run_ai_step_outcome CHECK (outcome IN ('handed', 'answered', 'failed', 'refused')),
    CONSTRAINT ck_run_ai_step_profile_source CHECK (profile_source IS NULL OR profile_source IN ('run', 'schedule')),
    -- A profile is asked by a run or a schedule, and nothing else asks one.
    CONSTRAINT ck_run_ai_step_profile_asked CHECK ((model_profile IS NULL) = (profile_source IS NULL)),
    CONSTRAINT ck_run_ai_step_model_choice CHECK (model_choice IS NULL OR model_choice IN ('prompt', 'default', 'override')),
    CONSTRAINT ck_run_ai_step_prompt_version CHECK (prompt_version IS NULL OR prompt_version > 0)
);

COMMENT ON TABLE public.run_ai_step IS 'One AI step of one run attempt: the model Core asked for (run or schedule) and, for a server step, what ai-service ran it on -- model, connection, option, how it was chosen, prompt version. The run''s manifest. Wave 4.';
COMMENT ON COLUMN public.run_ai_step.outcome IS 'handed = a worker step, told to the worker; answered / failed = the server step''s answer; refused = ai-service would not run it on that model (422).';
COMMENT ON COLUMN public.run_ai_step.model_option_id IS 'ai_db''s ai_step_model_option that chose the model, a plain id: another database, and the option may be gone.';

CREATE INDEX idx_run_ai_step_tenant ON public.run_ai_step (tenant_id);

CREATE TRIGGER run_ai_step_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_ai_step
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181): run_ai_step is a tenant table, so process_app sees and writes the session's workspace
-- only -- the same policy V181 gives every tenant table (platform-commons RowSecurity.OWNED_ROWS), ENABLE and FORCE.
-- Only where V181 has run in this database (process_app is a cluster-wide role, so its existence proves nothing here):
-- on a database V181 has not reached yet, V181's own loop covers this table when it runs, as it covers every tenant
-- table it finds, and it would refuse a policy already there.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security')
       AND NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_ai_step') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.run_ai_step TO process_app;
        GRANT USAGE, SELECT, UPDATE ON public.run_ai_step_seq TO process_app;
        ALTER TABLE public.run_ai_step ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.run_ai_step FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.run_ai_step TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
    END IF;
END $$;

-- ------------------------------------------------------------------------------------------------ result_record
ALTER TABLE public.result_record ADD COLUMN step_key character varying(128);
ALTER TABLE public.result_record ADD COLUMN model character varying(255);
ALTER TABLE public.result_record ADD COLUMN model_option_id bigint;
ALTER TABLE public.result_record ADD COLUMN model_choice character varying(16);
ALTER TABLE public.result_record ADD CONSTRAINT ck_result_record_model_choice
    CHECK (model_choice IS NULL OR model_choice IN ('prompt', 'default', 'override'));

COMMENT ON COLUMN public.result_record.step_key IS 'The AI step whose answer this result is (run_ai_step.step_key); null for a result no AI step made.';
COMMENT ON COLUMN public.result_record.model IS 'The model the AI step ran on, as ai-service answered it.';
COMMENT ON COLUMN public.result_record.model_option_id IS 'ai_db''s ai_step_model_option that chose the model, a plain id; null when none did.';
COMMENT ON COLUMN public.result_record.model_choice IS 'prompt = the prompt''s own connection or the workspace default, default = the step''s default option, override = the run or its schedule asked for it.';
