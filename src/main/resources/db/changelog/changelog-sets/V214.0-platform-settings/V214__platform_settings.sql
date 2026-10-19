-- V214: the platform and settings batch (owner decisions #21, #24 and #6, 2026-10-19).
--
--   service_health_sample loses response_ms, cpu_pct, threads and mean_ms: Platform › Reliability became Service health
--     (status, version, restarts, error rate, alerts, planned deploys), and the heap/CPU/request history it charted is
--     CloudWatch's on AWS. The heap columns stay: the memory rule reads them. Requests and errors a minute stay: the error rate.
--   alert_rule.muted_until: a rule muted until then tells nobody; its alerts still open and resolve (owner #6).
--   engine_setting_override: a workspace's own value of an engine setting that allows one (owner #24, EngineDial).
--   engine_setting_audit: every change and reset of an engine setting, the platform's and a workspace's, with the old and
--     new value, who and when.
--
-- The platform's engine values stay in orchestration_setting (one row per dial that differs from its default).

ALTER TABLE public.service_health_sample DROP COLUMN IF EXISTS response_ms;
ALTER TABLE public.service_health_sample DROP COLUMN IF EXISTS cpu_pct;
ALTER TABLE public.service_health_sample DROP COLUMN IF EXISTS threads;
ALTER TABLE public.service_health_sample DROP COLUMN IF EXISTS mean_ms;

ALTER TABLE public.alert_rule ADD COLUMN muted_until timestamp with time zone;
COMMENT ON COLUMN public.alert_rule.muted_until IS 'Owner #6: until then the rule tells nobody (bell, mail, webhooks); its alerts still open and resolve, and are told when the mute ends.';

CREATE TABLE public.engine_setting_override (
    tenant_id bigint NOT NULL,
    setting_key character varying(64) NOT NULL,
    setting_value character varying(32) NOT NULL,
    updated_by bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_engine_setting_override PRIMARY KEY (tenant_id, setting_key),
    CONSTRAINT ck_engine_setting_override_value CHECK (setting_value ~ '^[0-9]{1,18}$')
);
COMMENT ON TABLE public.engine_setting_override IS 'Owner #24: a workspace''s own value of an engine setting that allows one (EngineDial.perWorkspace); set by a platform administrator on Platform › Engine settings.';

CREATE SEQUENCE public.engine_setting_audit_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE public.engine_setting_audit (
    audit_id bigint DEFAULT nextval('public.engine_setting_audit_seq') NOT NULL,
    setting_key character varying(64) NOT NULL,
    tenant_id bigint,
    action character varying(16) NOT NULL,
    old_value character varying(32),
    new_value character varying(32),
    changed_by bigint,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_engine_setting_audit PRIMARY KEY (audit_id),
    CONSTRAINT ck_engine_setting_audit_action CHECK (action IN ('SET', 'RESET'))
);
CREATE INDEX idx_engine_setting_audit_key ON public.engine_setting_audit (setting_key, changed_at DESC);
COMMENT ON TABLE public.engine_setting_audit IS 'Owner #24: every change and reset of an engine setting -- the platform''s (tenant_id NULL) or one workspace''s -- with the old and new value, who and when.';

-- Row security (MIG-258): both are tenant tables (the audit's platform rows have no tenant and are read across workspaces).
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['engine_setting_override', 'engine_setting_audit'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
        GRANT USAGE, SELECT ON public.engine_setting_audit_seq TO process_app;
        GRANT DELETE ON public.orchestration_setting TO process_app;
    END IF;
END $$;
