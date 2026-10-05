-- MIG-278: forms shared by link with people outside the workspace. OFF by default: a workspace administrator turns
-- sharing on (form_share_policy), then creates links per form. A link's token is shown once and only its SHA-256 is
-- kept; a link expires, may allow one submission, may require sign-in, and can be revoked. A submission made through
-- a link names it (form_submission.share_link_id).

CREATE TABLE public.form_share_policy (
    tenant_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_form_share_policy PRIMARY KEY (tenant_id)
);
COMMENT ON TABLE public.form_share_policy IS 'MIG-278: whether a workspace lets its forms be shared by link (off unless an administrator turns it on).';

CREATE SEQUENCE public.form_share_link_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.form_share_link (
    link_id bigint DEFAULT nextval('public.form_share_link_seq'::regclass) NOT NULL,
    form_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    token_hash character(64) NOT NULL,
    label character varying(200),
    expires_at timestamp with time zone NOT NULL,
    max_submissions integer,
    used_count integer DEFAULT 0 NOT NULL,
    require_sign_in boolean DEFAULT false NOT NULL,
    status character varying(12) DEFAULT 'Active' NOT NULL,
    created_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    revoked_by bigint,
    revoked_at timestamp with time zone,
    last_used_at timestamp with time zone,
    CONSTRAINT pk_form_share_link PRIMARY KEY (link_id),
    CONSTRAINT ux_form_share_link_token UNIQUE (token_hash),
    CONSTRAINT fk_form_share_link_form_tenant FOREIGN KEY (form_id, tenant_id)
        REFERENCES public.form_definition (form_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_form_share_link_status CHECK (status IN ('Active', 'Revoked')),
    CONSTRAINT ck_form_share_link_max CHECK (max_submissions IS NULL OR max_submissions > 0),
    CONSTRAINT ck_form_share_link_used CHECK (used_count >= 0)
);
COMMENT ON TABLE public.form_share_link IS 'MIG-278: a form''s share links; only the token''s SHA-256 is kept.';
CREATE INDEX idx_form_share_link_form ON public.form_share_link (tenant_id, form_id);

ALTER TABLE public.form_submission ADD COLUMN share_link_id bigint;
ALTER TABLE public.form_submission ADD CONSTRAINT fk_form_submission_share_link FOREIGN KEY (share_link_id)
    REFERENCES public.form_share_link (link_id) ON DELETE SET NULL;
-- A file uploaded through a link is the visitor's who holds that link's ticket (they have no person id): only the same
-- ticket can send it with a submission.
ALTER TABLE public.form_upload ADD COLUMN share_ticket character varying(64);

COMMENT ON COLUMN public.form_submission.share_link_id IS 'MIG-278: the share link the submission came through; NULL for a signed-in member''s.';

-- Row security (MIG-258, V181), as V192: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_share_policy TO process_app;
        ALTER TABLE public.form_share_policy ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.form_share_policy FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.form_share_policy TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.form_share_link TO process_app;
        GRANT USAGE, SELECT, UPDATE ON public.form_share_link_seq TO process_app;
        ALTER TABLE public.form_share_link ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.form_share_link FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.form_share_link TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
    END IF;
END $$;
