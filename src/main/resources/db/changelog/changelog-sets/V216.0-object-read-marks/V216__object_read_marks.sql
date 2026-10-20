-- read_s3 "only new files since the last run" (2026-10-20): how far a job's read_s3 step has read a bucket prefix.
--
-- - run_object_read: per run and step, the bucket alias and the prefix as the step writes it (placeholders unfilled, so a run for one day or one file moves the job's mark), and the newest object it read --
--   its last-modified time (up_to_modified) and key (up_to_key), objects in time order and then by key. "Since the last
--   run" reads every object after the newest one a COMPLETED run of the job read with that step, bucket and prefix: a run
--   that failed or was interrupted moves nothing, so its files are read again by the next. A run that read nothing new
--   keeps the mark it started from.
--
-- Expand-only: one new table. The rules of process's tables as V207: tenant_id NOT NULL held to the parent's (V102's
-- tenant_id_from_parent and a composite foreign key), instants. A tenant table under row security (V181).
CREATE TABLE public.run_object_read (
    job_queue_id bigint NOT NULL,
    step_key character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    bucket_alias character varying(255) NOT NULL,
    prefix character varying(1024) NOT NULL,
    up_to_modified timestamp with time zone,
    up_to_key character varying(1024),
    objects_read bigint DEFAULT 0 NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_object_read PRIMARY KEY (job_queue_id, step_key),
    CONSTRAINT fk_run_object_read_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_object_read_counts CHECK (objects_read >= 0 AND attempt > 0),
    CONSTRAINT ck_run_object_read_mark CHECK ((up_to_modified IS NULL) = (up_to_key IS NULL))
);
COMMENT ON TABLE public.run_object_read IS 'read_s3 since the last run: the newest object (last-modified, key) a step read in a run under a bucket prefix; a completed run''s is the job''s watermark for that step.';
CREATE INDEX idx_run_object_read_job_step ON public.run_object_read (job_id, step_key, bucket_alias, prefix, up_to_modified DESC);
CREATE TRIGGER run_object_read_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_object_read
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181), as V207: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_object_read') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.run_object_read TO process_app;
            ALTER TABLE public.run_object_read ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.run_object_read FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.run_object_read TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
