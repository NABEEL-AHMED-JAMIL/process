-- "Since the last run" reads with runs side by side (owner 2026-10-21): which run took which item.
--
-- - run_read_take: per job, step and source (read_s3: "s3|<bucket alias>|<prefix as written>"; read_documents:
--   "documents|<document type>"), each item a run took ("<last-modified ms>:<key>" for an object, the dataset row for an
--   approved document) and the run that took it. Runs a workflow's requests start run side by side (V220), each from the
--   same watermark; an item a run in flight took is not another's, and an item a failed or interrupted run took is free
--   again -- read by the next run even when a later run's watermark passed it. A completed run's items are behind the
--   job's watermark, so its rows are forgotten at the next take; the table holds the takes of runs in flight and of runs
--   that failed.
--
-- Expand-only: one new table. The rules of process's tables as V216: tenant_id NOT NULL held to the run's (V102's
-- tenant_id_from_parent and a composite foreign key), instants. A tenant table under row security (V181).
CREATE TABLE public.run_read_take (
    job_id bigint NOT NULL,
    step_key character varying(64) NOT NULL,
    source character varying(1300) NOT NULL,
    item character varying(1100) NOT NULL,
    job_queue_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_read_take PRIMARY KEY (job_id, step_key, source, item),
    CONSTRAINT fk_run_read_take_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE
);
COMMENT ON TABLE public.run_read_take IS 'Since the last run, runs side by side: the item (object or approved document) a run in flight or a failed run took, per job, step and source.';
CREATE INDEX idx_run_read_take_run ON public.run_read_take (job_queue_id);
CREATE TRIGGER run_read_take_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_read_take
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181), as V216: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_read_take') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.run_read_take TO process_app;
            ALTER TABLE public.run_read_take ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.run_read_take FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.run_read_take TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
