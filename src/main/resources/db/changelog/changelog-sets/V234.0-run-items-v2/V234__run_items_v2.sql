-- MIG-421 Run Items view v2 (owner 2026-10-22): a schedule's items across its runs, compare two runs, bulk actions, an OCR
-- step, and reports kept on retry.
--
-- - run_item.fields: the item's own values as its last step with rows left them (scalars only, at most 40, each cut at 200
--   characters), read once when the item's attempt ends -- so a schedule's Items tab finds "risk = High" or an item's key
--   across its runs from the item records, never by opening each run's outputs. fields_ds is the run_dataset they were read
--   from: when the sweep removes that dataset (its retention), the values go with it (DatasetSweep). Masks apply on read.
-- - run_item.flag: 'check' -- the OCR step was less sure of the page than its threshold ("Check by hand", sent to Review).
-- - run_item.pipeline_version: the pipeline version the attempt ran (pipeline_definition.version), for the across-runs list
--   and the compare's "what changed in the pipeline".
-- - run_item_report: each report a Generate report step made for one item (one report per row), keyed by the item's tag (the
--   same in every attempt) and a hash of what it was made from (the row, the design, its published version, the step's
--   settings). A retry that reaches the step with the same hash reuses it -- media is not asked again, nothing is billed, the
--   report's version and signature are untouched. Pointers only: Report Studio's report id and version, the PDF's name.
--
-- Expand-only; tenant table under row security as V233.
ALTER TABLE public.run_item ADD COLUMN fields jsonb;
ALTER TABLE public.run_item ADD COLUMN fields_ds bigint;
ALTER TABLE public.run_item ADD COLUMN flag character varying(16);
ALTER TABLE public.run_item ADD COLUMN pipeline_version integer;
ALTER TABLE public.run_item ADD CONSTRAINT ck_run_item_flag CHECK (flag IS NULL OR flag IN ('check'));
COMMENT ON COLUMN public.run_item.fields IS 'MIG-421: the item''s scalar values from its last step with rows (at most 40, 200 characters each), for the across-runs search; cleared with the dataset they came from (fields_ds). Masked on read.';
COMMENT ON COLUMN public.run_item.flag IS 'MIG-421: check = an OCR step was less sure of this page than its threshold (Check by hand).';
CREATE INDEX idx_run_item_key ON public.run_item (tenant_id, item_key) WHERE current;
CREATE INDEX idx_run_item_fields_ds ON public.run_item (fields_ds) WHERE fields_ds IS NOT NULL;

CREATE TABLE public.run_item_report (
    run_item_report_id bigserial NOT NULL,
    job_queue_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    step_key character varying(64) NOT NULL,
    item_tag character varying(64) NOT NULL,
    item_key character varying(1100),
    data_hash character(64) NOT NULL,
    report_id character varying(64) NOT NULL,
    report_version integer NOT NULL,
    report_label character varying(255),
    file_name character varying(255),
    pages integer,
    made_attempt integer NOT NULL,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_run_item_report PRIMARY KEY (run_item_report_id),
    CONSTRAINT ux_run_item_report UNIQUE (job_queue_id, step_key, item_tag, data_hash),
    CONSTRAINT fk_run_item_report_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_item_report_version CHECK (report_version >= 1 AND made_attempt >= 1)
);
COMMENT ON TABLE public.run_item_report IS 'MIG-421: a report a Generate report step made for one item of a run (one report per row), by the item''s tag and a hash of its data, design and settings: a retry with the same hash reuses it (not made or billed again).';
CREATE INDEX idx_run_item_report_tenant ON public.run_item_report (tenant_id);
CREATE TRIGGER run_item_report_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_item_report
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181), as V233: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_item_report') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.run_item_report TO process_app;
            GRANT USAGE, SELECT, UPDATE ON SEQUENCE public.run_item_report_run_item_report_id_seq TO process_app;
            ALTER TABLE public.run_item_report ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.run_item_report FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.run_item_report TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
