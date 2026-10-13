-- MIG-354: data retention per workspace (2026-10-13). A workspace's administrators say how long each class of data is
-- kept (or forever); a daily pass removes what is older, in small batches, as the workspace, and records what it did.
--
--   retention_platform: the platform's own settings, one row -- the audit class's floor (6 years unless a platform
--     administrator changes it; no workspace may keep audit records for less) and the share of a class one pass may
--     remove before it stops and asks (20%).
--   retention_policy: a workspace's switch. No row, or enabled false, is "nothing is removed" -- every existing
--     workspace starts so, and only an administrator turns it on.
--   retention_rule: a workspace's days per class (NULL: forever), and an administrator's confirmation of a pass that
--     would remove more than the platform's share (confirmed_count: how many they saw; used once).
--   legal_hold: while one is active (released_at NULL) nothing in its scope is removed -- the whole workspace, one
--     pipeline (its runs, their outputs and logs, its forms' submissions and the files its runs took), or one run.
--   retention_pass, retention_pass_class: each pass and, per class, its cutoff, what was eligible, what it removed, the
--     bytes, the oldest item, and how it ended (DONE, PARTIAL: more next hour, HELD, PAUSED: over the share, FAILED,
--     OFF: forever, UNAVAILABLE: the owning service cannot say). One RUNNING pass per workspace (ux_retention_pass_running).
--   retention_audit: every change to the policy, the holds and the confirmations, and every pass's start and end, with
--     who did it. Kept as the audit class says (never under the floor).
--   job_queue.outputs_removed_at / logs_removed_at: when the policy removed a run's outputs or its logs. The run itself
--     (its id, pipeline, status and times) is kept: billing and the run history still name it.
--   alert_rule kind RETENTION: a workspace's alert when a pass fails or stops for confirmation (made with the policy).
--
-- Expand-only: new tables, two nullable columns, a widened check, and indexes (job_queue's in V212.1, concurrently).

CREATE TABLE public.retention_platform (
    id smallint DEFAULT 1 NOT NULL,
    audit_floor_days integer DEFAULT 2190 NOT NULL,
    max_share_pct integer DEFAULT 20 NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_retention_platform PRIMARY KEY (id),
    CONSTRAINT ck_retention_platform_one CHECK (id = 1),
    CONSTRAINT ck_retention_platform_floor CHECK (audit_floor_days BETWEEN 365 AND 36500),
    CONSTRAINT ck_retention_platform_share CHECK (max_share_pct BETWEEN 1 AND 100)
);
INSERT INTO public.retention_platform (id) VALUES (1);
COMMENT ON TABLE public.retention_platform IS 'MIG-354: the platform''s retention settings (one row): the audit class''s floor in days (2190, 6 years) and the share of a class one pass may remove before it asks (percent).';

CREATE TABLE public.retention_policy (
    tenant_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_retention_policy PRIMARY KEY (tenant_id)
);
COMMENT ON TABLE public.retention_policy IS 'MIG-354: a workspace''s retention switch; no row or enabled false removes nothing.';

CREATE TABLE public.retention_rule (
    tenant_id bigint NOT NULL,
    data_class character varying(32) NOT NULL,
    keep_days integer,
    confirmed_count bigint,
    confirmed_by bigint,
    confirmed_at timestamp with time zone,
    updated_by bigint,
    date_updated timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_retention_rule PRIMARY KEY (tenant_id, data_class),
    CONSTRAINT ck_retention_rule_class CHECK (data_class IN ('RUN_OUTPUTS', 'RUN_LOGS', 'FILES', 'DOCUMENTS', 'FORM_SUBMISSIONS',
        'AI_CONVERSATIONS', 'NOTIFICATIONS', 'AUDIT')),
    CONSTRAINT ck_retention_rule_days CHECK (keep_days IS NULL OR keep_days BETWEEN 1 AND 36500),
    CONSTRAINT ck_retention_rule_confirmed CHECK ((confirmed_count IS NULL) = (confirmed_at IS NULL))
);
COMMENT ON TABLE public.retention_rule IS 'MIG-354: a workspace''s days per data class (keep_days NULL: forever) and a one-time confirmation of a pass over the platform''s share.';

CREATE SEQUENCE public.legal_hold_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE public.legal_hold (
    legal_hold_id bigint DEFAULT nextval('public.legal_hold_seq') NOT NULL,
    tenant_id bigint NOT NULL,
    scope character varying(16) NOT NULL,
    job_id bigint,
    job_queue_id bigint,
    reason character varying(500) NOT NULL,
    placed_by bigint,
    placed_at timestamp with time zone DEFAULT now() NOT NULL,
    released_by bigint,
    released_at timestamp with time zone,
    release_reason character varying(500),
    CONSTRAINT pk_legal_hold PRIMARY KEY (legal_hold_id),
    CONSTRAINT ck_legal_hold_scope CHECK (scope IN ('WORKSPACE', 'PIPELINE', 'RUN')),
    CONSTRAINT ck_legal_hold_target CHECK ((scope = 'WORKSPACE' AND job_id IS NULL AND job_queue_id IS NULL)
        OR (scope = 'PIPELINE' AND job_id IS NOT NULL AND job_queue_id IS NULL)
        OR (scope = 'RUN' AND job_queue_id IS NOT NULL)),
    CONSTRAINT ck_legal_hold_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX idx_legal_hold_active ON public.legal_hold (tenant_id) WHERE released_at IS NULL;
CREATE INDEX idx_legal_hold_tenant ON public.legal_hold (tenant_id, placed_at DESC);
COMMENT ON TABLE public.legal_hold IS 'MIG-354: legal holds -- while released_at is NULL nothing in the scope (the workspace, a pipeline, a run) is removed by retention. A plain job_id / job_queue_id: a hold outlives the pipeline it names.';

CREATE SEQUENCE public.retention_pass_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE public.retention_pass (
    retention_pass_id bigint DEFAULT nextval('public.retention_pass_seq') NOT NULL,
    tenant_id bigint NOT NULL,
    started_how character varying(16) NOT NULL,
    state character varying(16) DEFAULT 'RUNNING' NOT NULL,
    started_by bigint,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    heartbeat_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    deleted bigint DEFAULT 0 NOT NULL,
    bytes bigint DEFAULT 0 NOT NULL,
    note character varying(1000),
    CONSTRAINT pk_retention_pass PRIMARY KEY (retention_pass_id),
    CONSTRAINT ux_retention_pass_id_tenant UNIQUE (retention_pass_id, tenant_id),
    CONSTRAINT ck_retention_pass_how CHECK (started_how IN ('SCHEDULE', 'MANUAL', 'RESUME')),
    CONSTRAINT ck_retention_pass_state CHECK (state IN ('RUNNING', 'DONE', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT ck_retention_pass_finished CHECK ((state = 'RUNNING') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX ux_retention_pass_running ON public.retention_pass (tenant_id) WHERE state = 'RUNNING';
CREATE INDEX idx_retention_pass_tenant ON public.retention_pass (tenant_id, started_at DESC);
COMMENT ON TABLE public.retention_pass IS 'MIG-354: a retention pass of one workspace: daily (SCHEDULE), by an administrator (MANUAL), or continuing one that stopped (RESUME); at most one RUNNING a workspace.';

CREATE TABLE public.retention_pass_class (
    retention_pass_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    data_class character varying(32) NOT NULL,
    state character varying(16) NOT NULL,
    keep_days integer,
    cutoff timestamp with time zone,
    eligible bigint DEFAULT 0 NOT NULL,
    total bigint DEFAULT 0 NOT NULL,
    deleted bigint DEFAULT 0 NOT NULL,
    bytes bigint DEFAULT 0 NOT NULL,
    oldest timestamp with time zone,
    note character varying(1000),
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    CONSTRAINT pk_retention_pass_class PRIMARY KEY (retention_pass_id, data_class),
    CONSTRAINT fk_retention_pass_class_pass FOREIGN KEY (retention_pass_id, tenant_id)
        REFERENCES public.retention_pass (retention_pass_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_retention_pass_class_state CHECK (state IN ('RUNNING', 'DONE', 'PARTIAL', 'HELD', 'PAUSED', 'FAILED', 'OFF', 'UNAVAILABLE'))
);
CREATE INDEX idx_retention_pass_class_tenant ON public.retention_pass_class (tenant_id, data_class, started_at DESC);
COMMENT ON TABLE public.retention_pass_class IS 'MIG-354: what one pass did with one class: the cutoff, what was eligible and in all, what it removed (items and bytes), the oldest eligible item, and how it ended.';

CREATE SEQUENCE public.retention_audit_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE public.retention_audit (
    retention_audit_id bigint DEFAULT nextval('public.retention_audit_seq') NOT NULL,
    tenant_id bigint NOT NULL,
    at timestamp with time zone DEFAULT now() NOT NULL,
    actor_user_id bigint,
    action character varying(32) NOT NULL,
    data_class character varying(32),
    retention_pass_id bigint,
    detail character varying(2000) NOT NULL,
    CONSTRAINT pk_retention_audit PRIMARY KEY (retention_audit_id)
);
CREATE INDEX idx_retention_audit_tenant ON public.retention_audit (tenant_id, at);
COMMENT ON TABLE public.retention_audit IS 'MIG-354: the retention audit -- policy changes, holds placed and released, confirmations, and each pass''s start and counts per class -- with who did it (actor_user_id NULL: the daily pass).';

-- The runs: what the policy removed, and when. The run's own row stays.
ALTER TABLE public.job_queue ADD COLUMN outputs_removed_at timestamp with time zone;
ALTER TABLE public.job_queue ADD COLUMN logs_removed_at timestamp with time zone;
COMMENT ON COLUMN public.job_queue.outputs_removed_at IS 'MIG-354: when the workspace''s retention policy removed this run''s outputs (datasets, results, AI answers); the run stays.';
COMMENT ON COLUMN public.job_queue.logs_removed_at IS 'MIG-354: when the workspace''s retention policy removed this run''s log lines and step logs; the run and its steps stay.';

-- The batches' reads: a workspace's submissions oldest first; a run's datasets by step (also the cascade's).
CREATE INDEX idx_form_submission_retention ON public.form_submission (tenant_id, submitted_at);
CREATE INDEX idx_run_dataset_step ON public.run_dataset (step_execution_id);

-- Alerts: a workspace's RETENTION rule (a pass failed, or stopped for confirmation).
ALTER TABLE public.alert_rule DROP CONSTRAINT ck_alert_rule_kind;
ALTER TABLE public.alert_rule ADD CONSTRAINT ck_alert_rule_kind CHECK (kind IN ('RUN_FAILED', 'SCHEDULE_MISSED', 'RUN_SLOW', 'REVIEW_WAITING',
    'RETENTION', 'SERVICE_UNHEALTHY', 'SERVICE_RESTARTED', 'MEMORY_HIGH', 'KAFKA_LAG', 'DISK_LOW', 'BILLING_MISMATCH'));

-- Row security (MIG-258): the tenant tables, as every other; retention_platform has no tenant (platform settings).
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT SELECT, UPDATE ON public.retention_platform TO process_app;
        FOREACH t IN ARRAY ARRAY['retention_policy', 'retention_rule', 'legal_hold', 'retention_pass', 'retention_pass_class',
                'retention_audit'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
        GRANT USAGE, SELECT ON public.legal_hold_seq, public.retention_pass_seq, public.retention_audit_seq TO process_app;
        -- The audit class's records may now be removed by the policy (never under the floor).
        GRANT DELETE ON public.retention_log, public.file_access_log TO process_app;
    END IF;
END $$;
