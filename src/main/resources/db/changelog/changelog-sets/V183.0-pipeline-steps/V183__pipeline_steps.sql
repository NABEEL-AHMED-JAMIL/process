-- MIG-230: pipelines as ordered steps, with step-level tracking, while legacy pipelines keep running unchanged.
--
-- - pipeline_definition: a pipeline's definition as JSON (source, ordered steps, settings; YAML is a view of it), one
--   row per save -- a version is never changed, so a run can name the definition it ran (step_execution's
--   pipeline_definition_id) and a retry runs the same one. The latest version is the pipeline's definition. A pipeline
--   with none (every pipeline today) is its legacy wrap: one 'legacy' step that runs today's path unchanged.
-- - step_execution (V180): what V180 left to the engine -- the step's key, the definition version, how many tries it
--   took, its on-error setting, and a status line (why a step was skipped, say).
-- - step_log: a step's own log lines, in order, shown on the step in the console's timeline. The run's audit log
--   (job_audit_logs) keeps its milestones (a step started, ended), as it keeps a worker's.
--
-- Expand-only: two new tables, five nullable (or defaulted) columns on a table no code has written yet. job_queue,
-- source_job, source_task, pipeline and the JobStatus lifecycle are untouched. The rules of process's tables as V180:
-- tenant_id NOT NULL held to the parent's by a composite foreign key and filled from it (V102's tenant_id_from_parent),
-- times are instants, sequences start at 1000, a status or vocabulary column takes only its spellings.

-- ------------------------------------------------------------------------------------------ pipeline_definition
CREATE SEQUENCE public.pipeline_definition_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.pipeline_definition (
    pipeline_definition_id bigint DEFAULT nextval('public.pipeline_definition_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    pipeline_key bigint NOT NULL,
    version integer NOT NULL,
    -- json, not jsonb: jsonb re-orders an object's keys, and a step's config may mean something by its order (a
    -- select step's {from: to} columns are output in the order written). The text is exactly what was validated.
    definition json NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_pipeline_definition PRIMARY KEY (pipeline_definition_id),
    -- One row per save: two editors saving at once cannot both be version n.
    CONSTRAINT ux_pipeline_definition_version UNIQUE (pipeline_key, version),
    -- A pipeline's definition is in the pipeline's workspace. No ON DELETE: a pipeline is deleted by its status, and
    -- a definition runs named it.
    CONSTRAINT fk_pipeline_definition_pipeline_tenant FOREIGN KEY (pipeline_key, tenant_id)
        REFERENCES public.pipeline (pipeline_key, tenant_id) ON UPDATE CASCADE,
    CONSTRAINT ck_pipeline_definition_version CHECK (version > 0),
    CONSTRAINT ck_pipeline_definition_object CHECK (json_typeof(definition) = 'object'),
    -- A CASE, not an AND: a CHECK passes on NULL (no steps at all), and json_array_length throws on a non-array.
    CONSTRAINT ck_pipeline_definition_steps CHECK (CASE WHEN json_typeof(definition -> 'steps') = 'array'
        THEN json_array_length(definition -> 'steps') > 0 ELSE false END)
);

COMMENT ON TABLE public.pipeline_definition IS 'A pipeline''s definition as ordered steps (source, steps, settings), one row per save; the latest version is the pipeline''s. A pipeline with none runs as its legacy wrap. MIG-230.';
COMMENT ON COLUMN public.pipeline_definition.definition IS 'process.pipeline.PipelineDefinition as JSON; YAML is a view of it. Validated by DefinitionValidator before it is written.';

CREATE INDEX idx_pipeline_definition_tenant ON public.pipeline_definition (tenant_id);

CREATE TRIGGER pipeline_definition_tenant_id_from_parent BEFORE INSERT OR UPDATE OF pipeline_key ON public.pipeline_definition
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('pipeline', 'pipeline_key', 'pipeline_key');

-- ------------------------------------------------------------------------------------------------ step_execution
ALTER TABLE public.step_execution ADD COLUMN step_key character varying(64);
ALTER TABLE public.step_execution ADD COLUMN pipeline_definition_id bigint;
ALTER TABLE public.step_execution ADD COLUMN tries integer DEFAULT 0 NOT NULL;
ALTER TABLE public.step_execution ADD COLUMN on_error character varying(16);
ALTER TABLE public.step_execution ADD COLUMN status_message character varying(1024);
ALTER TABLE public.step_execution ADD CONSTRAINT ck_step_execution_tries CHECK (tries >= 0);
ALTER TABLE public.step_execution ADD CONSTRAINT ck_step_execution_on_error CHECK (on_error IS NULL OR on_error IN ('fail', 'continue', 'skip_rest'));
-- A run's steps are named by their keys too: one key per attempt, as one index per attempt.
ALTER TABLE public.step_execution ADD CONSTRAINT ux_step_execution_run_step_key UNIQUE (job_queue_id, attempt, step_key);
ALTER TABLE public.step_execution ADD CONSTRAINT fk_step_execution_definition FOREIGN KEY (pipeline_definition_id)
    REFERENCES public.pipeline_definition (pipeline_definition_id);

COMMENT ON COLUMN public.step_execution.step_key IS 'The step''s key in its definition (steps[].key). MIG-230.';
COMMENT ON COLUMN public.step_execution.pipeline_definition_id IS 'The definition version the run follows; a retry of the run follows the same one. MIG-230.';
COMMENT ON COLUMN public.step_execution.tries IS 'How many times the step was tried in this attempt (its retry.maxAttempts bounds it); 0 for a step that never ran. MIG-230.';
COMMENT ON COLUMN public.step_execution.on_error IS 'What the step''s failure does to its run: fail, continue or skip_rest (process.pipeline.OnError). MIG-230.';
COMMENT ON COLUMN public.step_execution.status_message IS 'One line on the step''s status: why it was skipped, what it did. MIG-230.';

CREATE INDEX idx_step_execution_definition ON public.step_execution (pipeline_definition_id) WHERE pipeline_definition_id IS NOT NULL;

-- ------------------------------------------------------------------------------------------------------ step_log
CREATE SEQUENCE public.step_log_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.step_log (
    step_log_id bigint DEFAULT nextval('public.step_log_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    step_execution_id bigint NOT NULL,
    line_no integer NOT NULL,
    level character varying(8) DEFAULT 'INFO' NOT NULL,
    message text NOT NULL,
    logged_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_step_log PRIMARY KEY (step_log_id),
    CONSTRAINT ux_step_log_line UNIQUE (step_execution_id, line_no),
    -- A step's log is part of the step: in its workspace, and gone with it (and so with the run).
    CONSTRAINT fk_step_log_step_tenant FOREIGN KEY (step_execution_id, tenant_id)
        REFERENCES public.step_execution (step_execution_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_step_log_level CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    CONSTRAINT ck_step_log_line_no CHECK (line_no > 0)
);

COMMENT ON TABLE public.step_log IS 'One step''s log lines, in order (line_no), with their level and time: the step''s own console in the timeline. MIG-230.';

CREATE INDEX idx_step_log_tenant ON public.step_log (tenant_id);

CREATE TRIGGER step_log_tenant_id_from_parent BEFORE INSERT OR UPDATE OF step_execution_id ON public.step_log
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('step_execution', 'step_execution_id', 'step_execution_id');

-- Row security (MIG-258, V181): both new tables are tenant tables, so process_app sees and writes the session's
-- workspace only -- the policy V181 gives every tenant table (platform-commons RowSecurity.OWNED_ROWS), ENABLE and
-- FORCE. Only where V181 has run in this database (process_app is a cluster-wide role, so its existence proves
-- nothing here): on a database V181 has not reached yet, V181's own loop covers these tables when it runs, and it
-- would refuse a policy already there. As V182 does for run_ai_step.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['pipeline_definition', 'step_log'] LOOP
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
