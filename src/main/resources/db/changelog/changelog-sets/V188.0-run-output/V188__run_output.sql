-- Wave 4: a run's result manifest -- the files its steps produced. One row per step per attempt that wrote a file:
-- Save File's kept file (kind 'file': the run_dataset holding it, until the run's datasets expire) and Upload to
-- bucket's object (kind 'bucket': the workspace bucket alias and object key, which storage-service's browse endpoints
-- download). The step engine writes it as the step runs (process.pipeline.JdbcStepStore.output); the console reads it
-- through sourceJob.json/runOutputs.
--
-- Expand-only: one new table. The rules of process's tables as V180: tenant_id NOT NULL, held to the step's by a
-- composite foreign key and filled from it (V102's tenant_id_from_parent); instants; the sequence starts at 1000.
-- run_dataset_id is a plain bigint, not a foreign key: DatasetSweep removes an expired dataset's row, and the manifest
-- keeps saying what the step wrote (and that it has expired) after it has gone.

CREATE SEQUENCE public.run_output_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_output (
    run_output_id bigint DEFAULT nextval('public.run_output_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    step_execution_id bigint NOT NULL,
    kind character varying(16) NOT NULL,
    name character varying(255) NOT NULL,
    format character varying(16) NOT NULL,
    row_count bigint,
    byte_count bigint,
    run_dataset_id bigint,
    bucket_alias character varying(255),
    object_key character varying(1024),
    expires_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_output PRIMARY KEY (run_output_id),
    -- Once per step attempt: step_execution is one row per step of each attempt, and a step's later try replaces what
    -- an earlier try recorded.
    CONSTRAINT ux_run_output_step UNIQUE (step_execution_id),
    CONSTRAINT fk_run_output_step_tenant FOREIGN KEY (step_execution_id, tenant_id)
        REFERENCES public.step_execution (step_execution_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_output_kind CHECK (kind IN ('file', 'bucket')),
    CONSTRAINT ck_run_output_format CHECK (format IN ('csv', 'json', 'jsonl')),
    CONSTRAINT ck_run_output_counts CHECK ((row_count IS NULL OR row_count >= 0) AND (byte_count IS NULL OR byte_count >= 0)),
    -- A file is a run dataset; an upload is an object in a bucket.
    CONSTRAINT ck_run_output_file CHECK (kind <> 'file' OR (run_dataset_id IS NOT NULL AND bucket_alias IS NULL AND object_key IS NULL)),
    CONSTRAINT ck_run_output_bucket CHECK (kind <> 'bucket' OR (bucket_alias IS NOT NULL AND object_key IS NOT NULL AND run_dataset_id IS NULL))
);

COMMENT ON TABLE public.run_output IS 'A run''s result manifest (Wave 4): the file a step wrote -- a kept file (run_dataset) or an object uploaded to a workspace bucket -- with its format, rows and bytes. One row per step per attempt.';
COMMENT ON COLUMN public.run_output.run_dataset_id IS 'kind = file: the run_dataset holding it (not a foreign key: the dataset is swept at expires_at, the manifest row stays).';
COMMENT ON COLUMN public.run_output.bucket_alias IS 'kind = bucket: the workspace bucket alias the step wrote to, as storage-service names it.';

CREATE INDEX idx_run_output_tenant ON public.run_output (tenant_id);
CREATE INDEX idx_run_output_run_dataset ON public.run_output (run_dataset_id) WHERE run_dataset_id IS NOT NULL;

CREATE TRIGGER run_output_tenant_id_from_parent BEFORE INSERT OR UPDATE OF step_execution_id ON public.run_output
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('step_execution', 'step_execution_id', 'step_execution_id');

-- Row security (MIG-258, V181): a tenant table, so process_app sees and writes the session's workspace only -- the
-- policy V181 gives every tenant table, ENABLE and FORCE. Only where V181 has run in this database, as V182, V183 and
-- V186 do: on a database V181 has not reached yet, V181's own loop covers this table.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security')
       AND NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_output') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.run_output TO process_app;
        GRANT USAGE, SELECT, UPDATE ON public.run_output_seq TO process_app;
        ALTER TABLE public.run_output ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.run_output FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.run_output TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
    END IF;
END $$;
