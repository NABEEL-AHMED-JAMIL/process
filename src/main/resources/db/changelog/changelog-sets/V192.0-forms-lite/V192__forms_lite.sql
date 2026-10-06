-- Wave 5 Forms (lite), owner decision 2026-09-30: a workspace builds a form, shares it INSIDE the workspace (no public
-- or expiring links yet -- those wait for a security review), and each submission can start a pipeline.
--
--   form_definition: a form -- its name, description, status (Draft, Active, Archived), its fields in order (JSON, as
--                    process.forms.FormFields checks them) and, optionally, the job a submission starts. version goes
--                    up by one on every save, so a submission says which version of the fields it answered.
--   form_submission: one submission -- its answers (JSON, keyed by field), who sent it and when, and what it did:
--                    Received (stored; the form starts no job), RunStarted (the run, job_queue_id) or RunNotStarted
--                    (why not: the job was busy, paused, inactive, the workspace has no inbox ...). The file the run
--                    was given -- the submission as JSON in the workspace's inbox bucket -- is bucket/storage_key.
--
-- Expand-only: two new tables. The rules of process's tables as V180/V189: tenant_id NOT NULL; a submission's held to
-- its form's by a composite foreign key and filled from it (V102's tenant_id_from_parent); instants; a status column
-- takes only its spellings; sequences start at 1000. job_id and job_queue_id are plain bigints: the job or the run may
-- go, the form and the record stay (the service checks the job is the workspace's own on every save and submission).

CREATE SEQUENCE public.form_definition_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.form_definition (
    form_id bigint DEFAULT nextval('public.form_definition_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(120) NOT NULL,
    description character varying(2000),
    status character varying(16) NOT NULL,
    fields jsonb DEFAULT '[]'::jsonb NOT NULL,
    job_id bigint,
    version integer DEFAULT 1 NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_form_definition PRIMARY KEY (form_id),
    -- The parent side of form_submission's composite key.
    CONSTRAINT ux_form_definition_id_tenant UNIQUE (form_id, tenant_id),
    CONSTRAINT ck_form_definition_tenant CHECK (tenant_id > 0),
    CONSTRAINT ck_form_definition_status_enum CHECK (status IN ('Draft', 'Active', 'Archived')),
    CONSTRAINT ck_form_definition_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_form_definition_fields_array CHECK (jsonb_typeof(fields) = 'array'),
    CONSTRAINT ck_form_definition_version CHECK (version > 0)
);

-- One form by a name per workspace, whatever its case.
CREATE UNIQUE INDEX ux_form_definition_tenant_name ON public.form_definition (tenant_id, lower(name));

COMMENT ON TABLE public.form_definition IS 'Wave 5 Forms (lite): a workspace''s form -- name, status (Draft, Active, Archived), fields (JSON, in order) and the job a submission starts, if any. Shared inside the workspace only.';
COMMENT ON COLUMN public.form_definition.fields IS '[{key, label, type (text, longText, number, date, choice, yesNo, email), required, help, options}] as process.forms.FormFields checks them.';
COMMENT ON COLUMN public.form_definition.job_id IS 'The workspace''s job a submission starts, with the submission as its input file (job_queue.input_bucket/input_key). A plain id: the job may go, the form stays.';

CREATE SEQUENCE public.form_submission_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.form_submission (
    submission_id bigint DEFAULT nextval('public.form_submission_seq'::regclass) NOT NULL,
    form_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    form_version integer NOT NULL,
    answers jsonb NOT NULL,
    submitted_by bigint,
    submitted_by_name character varying(255),
    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(16) NOT NULL,
    job_id bigint,
    job_queue_id bigint,
    reason character varying(2000),
    bucket character varying(255),
    storage_key character varying(1024),
    date_updated timestamp with time zone,
    CONSTRAINT pk_form_submission PRIMARY KEY (submission_id),
    CONSTRAINT fk_form_submission_form_tenant FOREIGN KEY (form_id, tenant_id)
        REFERENCES public.form_definition (form_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_form_submission_status_enum CHECK (status IN ('Received', 'RunStarted', 'RunNotStarted')),
    CONSTRAINT ck_form_submission_answers_object CHECK (jsonb_typeof(answers) = 'object'),
    -- A started run is named; a run that did not start says why.
    CONSTRAINT ck_form_submission_started CHECK (status <> 'RunStarted' OR job_queue_id IS NOT NULL),
    CONSTRAINT ck_form_submission_not_started CHECK (status <> 'RunNotStarted' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

COMMENT ON TABLE public.form_submission IS 'Wave 5 Forms (lite): one submission of a form -- its answers (JSON), who sent it and when, and whether it started the form''s job (RunStarted, the run) or not (RunNotStarted, why).';

CREATE INDEX idx_form_submission_form ON public.form_submission (form_id, submitted_at DESC);
CREATE INDEX idx_form_submission_tenant ON public.form_submission (tenant_id);

CREATE TRIGGER form_submission_tenant_id_from_parent BEFORE INSERT OR UPDATE OF form_id ON public.form_submission
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('form_definition', 'form_id', 'form_id');

-- Row security (MIG-258, V181): tenant tables, so process_app sees and writes the session's workspace only -- the
-- policy V181 gives every tenant table, ENABLE and FORCE. Only where V181 has run in this database, as V186 and V188
-- do: on a database V181 has not reached yet, V181's own loop covers these tables.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'form_definition') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_definition TO process_app;
            GRANT USAGE, SELECT, UPDATE ON public.form_definition_seq TO process_app;
            ALTER TABLE public.form_definition ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.form_definition FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.form_definition TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'form_submission') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_submission TO process_app;
            GRANT USAGE, SELECT, UPDATE ON public.form_submission_seq TO process_app;
            ALTER TABLE public.form_submission ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.form_submission FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.form_submission TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
