-- MIG-243: two records. retention_log -- one row per run dataset the retention sweep (process.pipeline.DatasetSweep)
-- removed: whose, which run and step, the name, rows and storage key it had, when it expired and when it went.
-- file_access_log -- one row per download of a run dataset through the console (sourceJob.json/runDataset): who, which
-- dataset of which run, and whether it was served or refused (and why).
--
-- The sweep removes only what the step engine made (run_dataset, MIG-230/V183; the kept files V188's manifest names):
-- nothing else is ever deleted, and neither log holds a foreign key, since what they name is gone or may go.
--
-- Expand-only: two new tables. The rules of process's tables as V180: tenant_id NOT NULL (filled by the writer -- the
-- dataset's workspace), instants, the sequences start at 1000. Row security as V181 gives every tenant table, in the
-- guarded form V188 uses: only where V181 has run in this database.

CREATE SEQUENCE public.retention_log_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.retention_log (
    retention_log_id bigint DEFAULT nextval('public.retention_log_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    run_dataset_id bigint NOT NULL,
    job_queue_id bigint,
    step_execution_id bigint,
    step_key character varying(255),
    name character varying(255),
    storage_key character varying(1024) NOT NULL,
    row_count bigint,
    expires_at timestamp with time zone,
    removed_at timestamp with time zone DEFAULT now() NOT NULL,
    reason character varying(64) DEFAULT 'expired' NOT NULL,
    CONSTRAINT pk_retention_log PRIMARY KEY (retention_log_id)
);

COMMENT ON TABLE public.retention_log IS 'MIG-243: a run dataset the retention sweep removed (the file and its run_dataset row) -- whose, which run and step, what it held, when it expired and when it went.';

CREATE INDEX idx_retention_log_tenant ON public.retention_log (tenant_id, removed_at);
CREATE INDEX idx_retention_log_run ON public.retention_log (job_queue_id) WHERE job_queue_id IS NOT NULL;

CREATE SEQUENCE public.file_access_log_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.file_access_log (
    file_access_id bigint DEFAULT nextval('public.file_access_log_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint,
    action character varying(16) NOT NULL,
    kind character varying(16) NOT NULL,
    run_dataset_id bigint,
    job_queue_id bigint,
    file_name character varying(512),
    format character varying(16),
    outcome character varying(16) NOT NULL,
    http_status integer NOT NULL,
    reason character varying(512),
    accessed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_file_access_log PRIMARY KEY (file_access_id),
    CONSTRAINT ck_file_access_log_action CHECK (action IN ('download', 'preview')),
    CONSTRAINT ck_file_access_log_kind CHECK (kind IN ('run_dataset')),
    CONSTRAINT ck_file_access_log_outcome CHECK (outcome IN ('served', 'refused'))
);

COMMENT ON TABLE public.file_access_log IS 'MIG-243: every download of a run dataset through the console -- who, which dataset of which run, served or refused (and why).';

CREATE INDEX idx_file_access_log_tenant ON public.file_access_log (tenant_id, accessed_at);
CREATE INDEX idx_file_access_log_dataset ON public.file_access_log (run_dataset_id) WHERE run_dataset_id IS NOT NULL;

-- Row security (MIG-258, V181): tenant tables, so process_app sees and writes the session's workspace only -- the
-- policy V181 gives every tenant table, ENABLE and FORCE. Only where V181 has run in this database, as V188 does. A
-- record is never changed: process_app inserts and reads, and deletes nothing.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['retention_log', 'file_access_log'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT ON public.%I TO process_app', t);
                EXECUTE format('GRANT USAGE, SELECT ON public.%I TO process_app', t || '_seq');
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
