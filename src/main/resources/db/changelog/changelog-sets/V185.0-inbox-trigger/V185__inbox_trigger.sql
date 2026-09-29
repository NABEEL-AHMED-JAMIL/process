-- MIG-239, Core's half of the inbox. Storage announces each file that lands in a workspace's inbox
-- (platform.storage.inbox-arrived.v1: arrival id, workspace, the inbox connection's alias, the key under intake/, name,
-- size, type). Core starts every job of that workspace whose inbox trigger takes the file -- once per arrival.
--
-- - job_inbox_trigger: one per job. enabled, and file_pattern: a glob on the file's name (*.csv, invoices_*.pdf; case is
--   ignored), null for every file. Independent of the job's time schedule and of its execution (Auto or Manual): a
--   Manual job with a trigger runs on arrivals only.
-- - inbox_arrival: what each arrival did to each job it matched -- Started (naming its run) or Skipped (with the
--   reason: a run already in flight, the job not active, the workspace paused). UNIQUE (arrival_id, job_id) is what
--   makes "start once" hold when Kafka delivers the event again.
-- - job_queue.input_bucket / input_key: the file a run was started for (the inbox connection's alias and the object's
--   key). The dispatch payload carries them (inputBucket, inputKey) for the pipeline to read; null on every other run.
--
-- Expand-only: two new tables, a sequence and two nullable columns. The rules of process's tables as V180/V182:
-- tenant_id NOT NULL held to the job's (V102's tenant_id_from_parent and a composite foreign key), instants, sequences
-- from 1000; Storage's arrival id is a plain value (another database).

-- ------------------------------------------------------------------------------------------------ job_inbox_trigger
CREATE TABLE public.job_inbox_trigger (
    job_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    file_pattern character varying(255),
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_job_inbox_trigger PRIMARY KEY (job_id),
    CONSTRAINT fk_job_inbox_trigger_job_tenant FOREIGN KEY (job_id, tenant_id)
        REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    -- A name, never a path: the inbox's keys are Storage's own (intake/<date>/<arrival>-<name>).
    CONSTRAINT ck_job_inbox_trigger_file_pattern CHECK (file_pattern IS NULL OR (file_pattern <> '' AND strpos(file_pattern, '/') = 0))
);
COMMENT ON TABLE public.job_inbox_trigger IS 'MIG-239: start this job when a file arrives in its workspace''s inbox; file_pattern is a glob on the file''s name (null = every file).';
CREATE INDEX idx_job_inbox_trigger_tenant ON public.job_inbox_trigger (tenant_id) WHERE enabled;
CREATE TRIGGER job_inbox_trigger_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.job_inbox_trigger
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');

-- ---------------------------------------------------------------------------------------------------- inbox_arrival
CREATE SEQUENCE public.inbox_arrival_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.inbox_arrival (
    inbox_arrival_id bigint DEFAULT nextval('public.inbox_arrival_seq'::regclass) NOT NULL,
    arrival_id character varying(36) NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    bucket character varying(255) NOT NULL,
    storage_key character varying(1024) NOT NULL,
    file_name character varying(255) NOT NULL,
    bytes bigint NOT NULL,
    outcome character varying(16) NOT NULL,
    reason text,
    job_queue_id bigint,
    event_id character varying(64),
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_inbox_arrival PRIMARY KEY (inbox_arrival_id),
    -- Start once: one outcome per arrival per job, however often the event is delivered.
    CONSTRAINT ux_inbox_arrival_arrival_job UNIQUE (arrival_id, job_id),
    CONSTRAINT fk_inbox_arrival_job_tenant FOREIGN KEY (job_id, tenant_id)
        REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_inbox_arrival_outcome CHECK (outcome IN ('Started', 'Skipped')),
    -- A started arrival names its run (a plain id: a run may later be deleted); a skipped one never has one.
    CONSTRAINT ck_inbox_arrival_started_run CHECK ((outcome = 'Started') = (job_queue_id IS NOT NULL)),
    CONSTRAINT ck_inbox_arrival_bytes CHECK (bytes >= 0)
);
COMMENT ON TABLE public.inbox_arrival IS 'MIG-239: what one file''s arrival in the workspace''s inbox did to one job with an inbox trigger -- Started (its run) or Skipped (why). Unique per arrival and job.';
COMMENT ON COLUMN public.inbox_arrival.arrival_id IS 'Storage''s arrival id (storage_db.inbox_upload.arrival_id), a plain value: another database.';
CREATE INDEX idx_inbox_arrival_tenant_job ON public.inbox_arrival (tenant_id, job_id, date_created DESC);
CREATE TRIGGER inbox_arrival_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.inbox_arrival
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');

-- --------------------------------------------------------------------------------------------------------- job_queue
ALTER TABLE public.job_queue ADD COLUMN input_bucket character varying(255);
ALTER TABLE public.job_queue ADD COLUMN input_key character varying(1024);
COMMENT ON COLUMN public.job_queue.input_bucket IS 'MIG-239: the storage alias of the file this run was started for (an inbox arrival); null otherwise.';
COMMENT ON COLUMN public.job_queue.input_key IS 'MIG-239: the key of the file this run was started for, under intake/; null otherwise.';

-- Row security (MIG-258, V181): both tables are tenant tables, so process_app sees and writes the session's workspace
-- only -- the policy V181 gives every tenant table (platform-commons RowSecurity.OWNED_ROWS), ENABLE and FORCE. Only where
-- V181 has run in this database, as V182 does: on a database V181 has not reached yet, V181's own loop covers these
-- tables when it runs, and it would refuse a policy already there.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT USAGE, SELECT, UPDATE ON public.inbox_arrival_seq TO process_app;
        FOREACH t IN ARRAY ARRAY['job_inbox_trigger', 'inbox_arrival'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
