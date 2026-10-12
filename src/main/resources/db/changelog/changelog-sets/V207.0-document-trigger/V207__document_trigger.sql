-- MIG-364: approved documents feed pipelines. Document Intelligence (ai-service) publishes platform.document.approved.v1
-- when a document is approved -- by a reviewer, or on its own -- naming it (the extraction), its type and version; its
-- values are the type's dataset row, read through ai-service's trusted read. Core starts the jobs that asked for it, the
-- way an inbox arrival starts its jobs (V185, V204), and a step reads a type's dataset (read_documents).
--
-- - job_document_trigger: one per job. "When a document of type X is approved": the type (an ai-service id, a plain
--   value: another database), on or off, and how many waiting documents one run takes (1..50; 1 = a run per document).
-- - document_approval: what each approval did to each job it matched -- Started (naming its run), Waiting (the job was
--   busy or the workspace paused: the job's next run takes it) or Skipped (with the reason: the job switched off or gone).
--   UNIQUE (extraction_id, type_version, job_id) is what makes "start once per document and version" hold when Kafka
--   delivers the event again. The run's documents are the rows that name it (idx_document_approval_run).
-- - run_document_read: the documents a read_documents step read, per run and step -- the type and the highest dataset row
--   it read (up_to). "Since the last run" is every row after the highest one a COMPLETED run of the job read with that
--   step: a run that failed or was interrupted moves nothing, so its documents are read again by the next.
--
-- Expand-only: three new tables and their sequences. The rules of process's tables as V185/V206: tenant_id NOT NULL held
-- to the parent's (V102's tenant_id_from_parent and a composite foreign key), instants, sequences from 1000; ai-service's
-- ids are plain values. All three are tenant tables under row security (V181).

-- ------------------------------------------------------------------------------------------------ job_document_trigger
CREATE TABLE public.job_document_trigger (
    job_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    document_type_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    batch_size integer DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_job_document_trigger PRIMARY KEY (job_id),
    CONSTRAINT fk_job_document_trigger_job_tenant FOREIGN KEY (job_id, tenant_id)
        REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_job_document_trigger_type CHECK (document_type_id > 0),
    CONSTRAINT ck_job_document_trigger_batch_size CHECK (batch_size BETWEEN 1 AND 50)
);
COMMENT ON TABLE public.job_document_trigger IS 'MIG-364: start this job when a document of this type is approved (Document Intelligence); batch_size waiting documents per run.';
COMMENT ON COLUMN public.job_document_trigger.document_type_id IS 'ai_db.ai_document_type.document_type_id, a plain value: another database.';
CREATE INDEX idx_job_document_trigger_type ON public.job_document_trigger (tenant_id, document_type_id) WHERE enabled;
CREATE TRIGGER job_document_trigger_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.job_document_trigger
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');

-- --------------------------------------------------------------------------------------------------- document_approval
CREATE SEQUENCE public.document_approval_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.document_approval (
    document_approval_id bigint DEFAULT nextval('public.document_approval_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    extraction_id bigint NOT NULL,
    type_version integer NOT NULL,
    document_type_id bigint NOT NULL,
    ocr_document_id bigint,
    approval character varying(16),
    outcome character varying(16) NOT NULL,
    reason text,
    job_queue_id bigint,
    event_id character varying(64),
    started_at timestamp with time zone,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_document_approval PRIMARY KEY (document_approval_id),
    -- Start once: one outcome per document, version and job, however often the event is delivered.
    CONSTRAINT ux_document_approval_document_job UNIQUE (extraction_id, type_version, job_id),
    CONSTRAINT fk_document_approval_job_tenant FOREIGN KEY (job_id, tenant_id)
        REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_document_approval_outcome CHECK (outcome IN ('Started', 'Waiting', 'Skipped')),
    -- A started approval names its run (a plain id: a run may later be deleted); a waiting or skipped one never has one.
    CONSTRAINT ck_document_approval_started_run CHECK ((outcome = 'Started') = (job_queue_id IS NOT NULL))
);
COMMENT ON TABLE public.document_approval IS 'MIG-364: what one approved document did to one job with a document trigger -- Started (its run), Waiting (the job''s next run takes it) or Skipped (why). Unique per document, version and job.';
COMMENT ON COLUMN public.document_approval.extraction_id IS 'ai_db.ai_document_extraction.extraction_id (the document the review screen opens), a plain value: another database.';
CREATE INDEX idx_document_approval_tenant_job ON public.document_approval (tenant_id, job_id, date_created DESC);
CREATE INDEX idx_document_approval_waiting ON public.document_approval (job_id, document_approval_id) WHERE outcome = 'Waiting';
CREATE INDEX idx_document_approval_run ON public.document_approval (job_queue_id) WHERE job_queue_id IS NOT NULL;
CREATE TRIGGER document_approval_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.document_approval
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');

-- --------------------------------------------------------------------------------------------------- run_document_read
CREATE TABLE public.run_document_read (
    job_queue_id bigint NOT NULL,
    step_key character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    document_type_id bigint NOT NULL,
    up_to bigint NOT NULL,
    rows_read bigint DEFAULT 0 NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_document_read PRIMARY KEY (job_queue_id, step_key),
    CONSTRAINT fk_run_document_read_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_document_read_counts CHECK (up_to >= 0 AND rows_read >= 0 AND attempt > 0)
);
COMMENT ON TABLE public.run_document_read IS 'MIG-364: the highest dataset row of a document type a read_documents step read in a run; a completed run''s is the job''s watermark for that step.';
CREATE INDEX idx_run_document_read_job_step ON public.run_document_read (job_id, step_key, document_type_id, up_to DESC);
CREATE TRIGGER run_document_read_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_document_read
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181), as V185 and V206: only where V181 has run in this database.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT USAGE, SELECT, UPDATE ON public.document_approval_seq TO process_app;
        FOREACH t IN ARRAY ARRAY['job_document_trigger', 'document_approval', 'run_document_read'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
