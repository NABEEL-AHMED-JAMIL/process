-- MIG-420 Run Items view (owner 2026-10-21): every item a pipeline processes -- one input file, recording, document or
-- row of a per-row step -- with its status, the step it failed at and why, each step's data, its files and its timings.
--
-- - run_item: one row per item per attempt that ran it. item_key is the item's identity in the run (an object key, a
--   document id, a row's key; a sub-item is its parent's key, '#', and its own); item_ref the short id the console and the
--   customer API name it by ("3", a sub-item "3.12"), the same in every attempt. A retry of the item is a new row under the
--   new attempt; the earlier one is kept, current = false, for audit. steps holds, per step, a pointer into the step's
--   kept output (run_dataset id and row positions) -- never a copy of the data, which keeps its own retention.
-- - run_item_file: the files an item has -- its input object, and the files steps made for it (the run_output of that
--   step execution and name, or a bucket object). Pointers only.
--
-- Only item pipelines have items (StepEngine, process.pipeline.items): a pipeline with a per-item step (transcribe, convert,
-- AI prompt, measure, Call API per row, one file or report per row, split pages), at most 1,000 items and 10,000 sub-items
-- a run. A big table read whole never makes a row here.
--
-- Expand-only. The rules of process's tables as V221: tenant_id NOT NULL held to the run's (V102's tenant_id_from_parent
-- and a composite foreign key), instants. Tenant tables under row security (V181).
CREATE TABLE public.run_item (
    run_item_id bigserial NOT NULL,
    job_queue_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    attempt integer NOT NULL,
    item_key character varying(1100) NOT NULL,
    item_ref character varying(32) NOT NULL,
    parent_key character varying(1100),
    label character varying(512) NOT NULL,
    position integer NOT NULL,
    status character varying(16) NOT NULL,
    failed_step_index integer,
    failed_step_key character varying(64),
    reason character varying(2000),
    steps jsonb DEFAULT '[]'::jsonb NOT NULL,
    child_count integer DEFAULT 0 NOT NULL,
    child_failed integer DEFAULT 0 NOT NULL,
    file_count integer DEFAULT 0 NOT NULL,
    current boolean DEFAULT true NOT NULL,
    started_at timestamp with time zone,
    ended_at timestamp with time zone,
    duration_ms bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_item PRIMARY KEY (run_item_id),
    CONSTRAINT ux_run_item_key_attempt UNIQUE (job_queue_id, item_key, attempt),
    CONSTRAINT fk_run_item_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_item_status CHECK (status IN ('running', 'done', 'failed')),
    CONSTRAINT ck_run_item_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_run_item_failed CHECK (status <> 'failed' OR reason IS NOT NULL)
);
COMMENT ON TABLE public.run_item IS 'MIG-420: an item a pipeline run processed (a file, recording, document or row of a per-row step; a sub-item under its parent), per attempt: status, failed step, reason, each step''s pointer into its kept output, timings. current = the latest attempt that ran it.';
COMMENT ON COLUMN public.run_item.steps IS 'Per step: [{i, key, task, status, note, ms, ds (run_dataset id), rows (positions), n (rows), sharedBy, files, reusedFrom}] -- pointers, never the data.';
CREATE INDEX idx_run_item_run ON public.run_item (job_queue_id, position) WHERE current;
CREATE INDEX idx_run_item_run_ref ON public.run_item (job_queue_id, item_ref);
CREATE INDEX idx_run_item_failed ON public.run_item (tenant_id, ended_at) WHERE current AND status = 'failed';
CREATE INDEX idx_run_item_tenant ON public.run_item (tenant_id);
CREATE TRIGGER run_item_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_item
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

CREATE TABLE public.run_item_file (
    run_item_file_id bigserial NOT NULL,
    job_queue_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    attempt integer NOT NULL,
    item_key character varying(1100) NOT NULL,
    step_index integer NOT NULL,
    step_key character varying(64) NOT NULL,
    role character varying(16) NOT NULL,
    step_execution_id bigint,
    file_name character varying(512) NOT NULL,
    bucket_alias character varying(255),
    object_key character varying(1100),
    byte_count bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_run_item_file PRIMARY KEY (run_item_file_id),
    CONSTRAINT fk_run_item_file_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_item_file_role CHECK (role IN ('input', 'output')),
    CONSTRAINT ck_run_item_file_where CHECK (step_execution_id IS NOT NULL OR (bucket_alias IS NOT NULL AND object_key IS NOT NULL))
);
COMMENT ON TABLE public.run_item_file IS 'MIG-420: an item''s files -- its input object (bucket alias and key) and each file a step made for it (run_output by step execution and name, or a bucket object). Pointers only.';
CREATE INDEX idx_run_item_file_item ON public.run_item_file (job_queue_id, item_key, attempt);
CREATE INDEX idx_run_item_file_tenant ON public.run_item_file (tenant_id);
CREATE TRIGGER run_item_file_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_item_file
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- A retry of some of a run's items (MIG-420): which items, and whether each runs whole (from the step after the one that
-- found it) or from the step it failed at. Null for every other attempt.
ALTER TABLE public.run_attempt ADD COLUMN item_keys jsonb;
ALTER TABLE public.run_attempt ADD COLUMN item_mode character varying(16);
ALTER TABLE public.run_attempt ADD CONSTRAINT ck_run_attempt_item_mode
    CHECK ((item_keys IS NULL AND item_mode IS NULL) OR (item_keys IS NOT NULL AND item_mode IN ('item', 'failed_step') AND mode = 'resume'));

-- run.item.completed (MIG-420): CustomerEventRelay reads the item and sends its event.
ALTER TABLE public.api_event_out ADD COLUMN run_item_id bigint;
ALTER TABLE public.api_event_out DROP CONSTRAINT ck_api_event_out_kind;
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_kind
    CHECK (kind IN ('run_status', 'file_made', 'review_decided', 'submission', 'alert', 'item'));
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_item CHECK ((kind = 'item') = (run_item_id IS NOT NULL));

-- Every item file opened or downloaded, every zip of them, and every signed link made for one, served or refused.
ALTER TABLE public.file_access_log DROP CONSTRAINT ck_file_access_log_kind;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_kind CHECK (kind IN ('run_dataset', 'api_file', 'run_item_file', 'run_item_zip'));
ALTER TABLE public.file_access_log DROP CONSTRAINT ck_file_access_log_action;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_action CHECK (action IN ('download', 'preview', 'link'));

-- Row security (MIG-258, V181), as V221: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['run_item', 'run_item_file'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('GRANT USAGE, SELECT, UPDATE ON SEQUENCE public.%I TO process_app', t || '_' || t || '_id_seq');
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
