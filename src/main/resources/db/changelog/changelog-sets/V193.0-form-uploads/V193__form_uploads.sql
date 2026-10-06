-- MIG-277: form versions, and files and signatures on a form.
--
--   form_version: the fields a form had at each version (every save bumps form_definition.version), so a submission is
--                 shown with the fields it answered, not today's. Filled here for every form's current version.
--
-- Files and signatures: A person filling in a form uploads each file (or their drawn signature)
-- before sending it; the upload is written to the workspace's inbox bucket beside the submissions, and recorded here so
-- a submission can only name files that the same person uploaded to the same form's field -- and each file once.
--
--   form_upload: one uploaded file -- the form and field it is for, who uploaded it, its name, type and size, where it
--                is (bucket/storage_key), and the submission that took it (NULL until one does).
--
-- Expand-only: two new tables, the rules of V192 (tenant_id NOT NULL, filled from its form by the composite key and
-- tenant_id_from_parent; instants; sequence from 1000; row security as V181).

CREATE TABLE public.form_version (
    form_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    version integer NOT NULL,
    name character varying(120) NOT NULL,
    fields jsonb NOT NULL,
    saved_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_form_version PRIMARY KEY (form_id, version),
    CONSTRAINT fk_form_version_form_tenant FOREIGN KEY (form_id, tenant_id)
        REFERENCES public.form_definition (form_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_form_version_fields_array CHECK (jsonb_typeof(fields) = 'array')
);

COMMENT ON TABLE public.form_version IS 'MIG-277: the fields a form had at each version, so a submission is shown with the fields it answered.';

CREATE INDEX idx_form_version_tenant ON public.form_version (tenant_id);

CREATE TRIGGER form_version_tenant_id_from_parent BEFORE INSERT OR UPDATE OF form_id ON public.form_version
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('form_definition', 'form_id', 'form_id');

INSERT INTO public.form_version (form_id, tenant_id, version, name, fields, saved_by, date_created)
SELECT form_id, tenant_id, version, name, fields, updated_by, coalesce(date_updated, date_created) FROM public.form_definition;

CREATE SEQUENCE public.form_upload_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.form_upload (
    upload_id bigint DEFAULT nextval('public.form_upload_seq'::regclass) NOT NULL,
    form_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    field_key character varying(40) NOT NULL,
    uploaded_by bigint,
    file_name character varying(255) NOT NULL,
    content_type character varying(120) NOT NULL,
    size_bytes bigint NOT NULL,
    bucket character varying(255) NOT NULL,
    storage_key character varying(1024) NOT NULL,
    submission_id bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_form_upload PRIMARY KEY (upload_id),
    CONSTRAINT fk_form_upload_form_tenant FOREIGN KEY (form_id, tenant_id)
        REFERENCES public.form_definition (form_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_form_upload_submission FOREIGN KEY (submission_id)
        REFERENCES public.form_submission (submission_id) ON DELETE SET NULL,
    CONSTRAINT ck_form_upload_size CHECK (size_bytes >= 0)
);

COMMENT ON TABLE public.form_upload IS 'MIG-277: a file or signature uploaded while filling in a form -- the field it is for, who uploaded it, where it is in the inbox bucket, and the submission that took it (NULL until one does).';

CREATE INDEX idx_form_upload_form ON public.form_upload (form_id, field_key);
CREATE INDEX idx_form_upload_submission ON public.form_upload (submission_id);
CREATE INDEX idx_form_upload_tenant ON public.form_upload (tenant_id);

CREATE TRIGGER form_upload_tenant_id_from_parent BEFORE INSERT OR UPDATE OF form_id ON public.form_upload
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('form_definition', 'form_id', 'form_id');

DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'form_version') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_version TO process_app;
            ALTER TABLE public.form_version ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.form_version FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.form_version TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'form_upload') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_upload TO process_app;
            GRANT USAGE, SELECT, UPDATE ON public.form_upload_seq TO process_app;
            ALTER TABLE public.form_upload ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.form_upload FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.form_upload TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
