-- MIG-355: monitoring that alerts (2026-10-13). Nothing here is a time-series store: every table is small and bounded.
--
--   alert_rule: what to watch and who to tell. A workspace's rules (tenant_id set): a run failed, or N failures in X
--     minutes; a schedule's slot with no run; a run slower than its pipeline's usual (p95); a review or approval waiting
--     too long. The platform's rules (tenant_id NULL, platform administrators): a service unhealthy, a service restarted,
--     memory high, Kafka consumer lag, disk low, a billing reconciliation that does not agree. At most 50 a workspace
--     (the API's rule). Delivery: the bell, mail, and a workspace's webhooks; recipients by role and by group (an access
--     profile); quiet hours hold mail, never the bell.
--   alert_incident: one OPEN alert per rule and subject (ux_alert_incident_open) -- the de-duplication: a condition that
--     holds again only touches it (last_seen_at, occurrences). Resolved by the evaluator when the condition clears (AUTO)
--     or by a person (MANUAL); resolved alerts are removed after 30 days, open ones are never removed.
--   service_health_sample: each service's health once a minute -- status, heap, CPU, request and error rates, mean
--     latency, its JVM's start (a restart is a new start) -- kept 24 hours (about 18,000 rows for 13 services).
--   platform_probe: the latest reading of each platform probe (Kafka lag per consumer group, disk, billing reconciliation),
--     one row per probe, overwritten.
--   api_event_out kind 'alert': a workspace rule with the webhook channel writes its alert.opened / alert.resolved here,
--     and CustomerEventRelay sends it as any customer event (signed, retried, guarded by integration-service).
--
-- Expand-only: new tables, new nullable columns, a widened check and partial indexes.

CREATE SEQUENCE public.alert_rule_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.alert_rule (
    alert_rule_id bigint DEFAULT nextval('public.alert_rule_seq') NOT NULL,
    tenant_id bigint,
    kind character varying(32) NOT NULL,
    name character varying(120) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    severity character varying(16) DEFAULT 'WARNING' NOT NULL,
    job_id bigint,
    threshold integer DEFAULT 1 NOT NULL,
    window_minutes integer DEFAULT 60 NOT NULL,
    factor numeric(6,2),
    notify_bell boolean DEFAULT true NOT NULL,
    notify_email boolean DEFAULT false NOT NULL,
    notify_webhook boolean DEFAULT false NOT NULL,
    recipient_roles character varying(64) DEFAULT 'TENANT_ADMIN' NOT NULL,
    recipient_groups character varying(512),
    quiet_start time without time zone,
    quiet_end time without time zone,
    quiet_zone character varying(64),
    created_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone,
    CONSTRAINT pk_alert_rule PRIMARY KEY (alert_rule_id),
    CONSTRAINT ck_alert_rule_kind CHECK (kind IN ('RUN_FAILED', 'SCHEDULE_MISSED', 'RUN_SLOW', 'REVIEW_WAITING',
        'SERVICE_UNHEALTHY', 'SERVICE_RESTARTED', 'MEMORY_HIGH', 'KAFKA_LAG', 'DISK_LOW', 'BILLING_MISMATCH')),
    CONSTRAINT ck_alert_rule_scope CHECK ((tenant_id IS NULL) = (kind IN ('SERVICE_UNHEALTHY', 'SERVICE_RESTARTED', 'MEMORY_HIGH',
        'KAFKA_LAG', 'DISK_LOW', 'BILLING_MISMATCH'))),
    CONSTRAINT ck_alert_rule_severity CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT ck_alert_rule_numbers CHECK (threshold BETWEEN 1 AND 1000000 AND window_minutes BETWEEN 1 AND 10080
        AND (factor IS NULL OR factor BETWEEN 1 AND 100)),
    CONSTRAINT ck_alert_rule_quiet CHECK ((quiet_start IS NULL) = (quiet_end IS NULL)),
    CONSTRAINT ck_alert_rule_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_alert_rule_job_scope CHECK (job_id IS NULL OR tenant_id IS NOT NULL),
    CONSTRAINT fk_alert_rule_job_tenant FOREIGN KEY (job_id, tenant_id) REFERENCES public.source_job (job_id, tenant_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX idx_alert_rule_tenant ON public.alert_rule (tenant_id);
COMMENT ON TABLE public.alert_rule IS 'MIG-355: a workspace''s alert rules (tenant_id set) and the platform''s (tenant_id NULL): what to watch, how much is too much, and who hears of it by bell, mail and webhook.';
COMMENT ON COLUMN public.alert_rule.threshold IS 'RUN_FAILED: failures in the window; SERVICE_UNHEALTHY: minutes down; MEMORY_HIGH and DISK_LOW: percent; KAFKA_LAG: messages behind; others: unused (1).';
COMMENT ON COLUMN public.alert_rule.window_minutes IS 'RUN_FAILED and SCHEDULE_MISSED: how far back; REVIEW_WAITING: how long a review may wait; SERVICE_RESTARTED: how long a restart stays an alert.';
COMMENT ON COLUMN public.alert_rule.factor IS 'RUN_SLOW: a run is slow past factor x its pipeline''s p95 over 30 days.';
COMMENT ON COLUMN public.alert_rule.recipient_roles IS 'Comma list of TENANT_ADMIN, TENANT_USER (a workspace rule); a platform rule tells every platform administrator.';
COMMENT ON COLUMN public.alert_rule.recipient_groups IS 'Comma list of access profile ids (groups) whose members are told as well.';
COMMENT ON COLUMN public.alert_rule.quiet_start IS 'Quiet hours, wall clock in quiet_zone: mail is held until they end (sent then if the alert is still open); the bell and webhooks are not held.';

CREATE SEQUENCE public.alert_incident_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.alert_incident (
    alert_incident_id bigint DEFAULT nextval('public.alert_incident_seq') NOT NULL,
    tenant_id bigint,
    alert_rule_id bigint NOT NULL,
    kind character varying(32) NOT NULL,
    subject character varying(160) NOT NULL,
    state character varying(16) DEFAULT 'OPEN' NOT NULL,
    severity character varying(16) NOT NULL,
    title character varying(300) NOT NULL,
    detail character varying(2000),
    link character varying(500),
    job_id bigint,
    job_queue_id bigint,
    occurrences integer DEFAULT 1 NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    notified_at timestamp with time zone,
    email_held boolean DEFAULT false NOT NULL,
    emailed_at timestamp with time zone,
    resolved_at timestamp with time zone,
    resolved_how character varying(16),
    resolved_by bigint,
    CONSTRAINT pk_alert_incident PRIMARY KEY (alert_incident_id),
    CONSTRAINT fk_alert_incident_rule FOREIGN KEY (alert_rule_id) REFERENCES public.alert_rule (alert_rule_id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_incident_state CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_alert_incident_severity CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT ck_alert_incident_resolved CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_alert_incident_how CHECK (resolved_how IS NULL OR resolved_how IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_alert_incident_occurrences CHECK (occurrences >= 1)
);
CREATE UNIQUE INDEX ux_alert_incident_open ON public.alert_incident (alert_rule_id, subject) WHERE state = 'OPEN';
CREATE INDEX idx_alert_incident_tenant ON public.alert_incident (tenant_id, state, last_seen_at);
CREATE INDEX idx_alert_incident_resolved ON public.alert_incident (resolved_at) WHERE state = 'RESOLVED';
COMMENT ON TABLE public.alert_incident IS 'MIG-355: alerts -- one OPEN row per rule and subject (the de-duplication); resolved by the evaluator (AUTO) or a person (MANUAL); resolved rows removed after 30 days.';
COMMENT ON COLUMN public.alert_incident.email_held IS 'Mail held by the rule''s quiet hours; the evaluator sends it when they end, if the alert is still open.';

CREATE TABLE public.service_health_sample (
    service character varying(40) NOT NULL,
    sampled_at timestamp with time zone NOT NULL,
    status character varying(16) NOT NULL,
    source character varying(16) NOT NULL,
    response_ms integer,
    heap_used_mb integer,
    heap_max_mb integer,
    heap_after_gc_pct numeric(5,1),
    cpu_pct numeric(5,1),
    threads integer,
    started_at timestamp with time zone,
    requests_per_min numeric(12,2),
    errors_per_min numeric(12,2),
    mean_ms numeric(12,1),
    CONSTRAINT pk_service_health_sample PRIMARY KEY (service, sampled_at),
    CONSTRAINT ck_service_health_sample_status CHECK (status IN ('UP', 'DOWN', 'OUT_OF_SERVICE', 'UNREACHABLE', 'UNKNOWN')),
    CONSTRAINT ck_service_health_sample_source CHECK (source IN ('METRICS', 'HEALTH'))
);
CREATE INDEX idx_service_health_sample_at ON public.service_health_sample (sampled_at);
COMMENT ON TABLE public.service_health_sample IS 'MIG-355: each service''s health once a minute (the platform''s, no workspace); kept 24 hours.';

CREATE TABLE public.platform_probe (
    probe character varying(80) NOT NULL,
    read_at timestamp with time zone NOT NULL,
    ok boolean NOT NULL,
    reading numeric(20,2),
    detail character varying(2000),
    CONSTRAINT pk_platform_probe PRIMARY KEY (probe)
);
COMMENT ON TABLE public.platform_probe IS 'MIG-355: the latest reading of each platform probe (kafka-lag:<group>, disk:<path>, billing-reconcile), overwritten each time.';

-- A webhook for an alert: CustomerEventRelay reads the incident and sends alert.opened or alert.resolved.
ALTER TABLE public.api_event_out ADD COLUMN alert_incident_id bigint;
ALTER TABLE public.api_event_out ADD COLUMN alert_event character varying(16);
ALTER TABLE public.api_event_out DROP CONSTRAINT ck_api_event_out_kind;
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_kind
    CHECK (kind IN ('run_status', 'file_made', 'review_decided', 'submission', 'alert'));
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_alert
    CHECK ((kind = 'alert') = (alert_incident_id IS NOT NULL AND alert_event IN ('opened', 'resolved')));

-- What the rules ask, each a small partial index: the failed runs of a window, the skipped and missed slots, the reviews
-- and approvals still waiting.
CREATE INDEX idx_job_queue_failed_ended ON public.job_queue ((COALESCE(end_time, skip_time)))
    WHERE job_status IN ('Failed', 'Interrupt');
CREATE INDEX idx_job_queue_skipped_at ON public.job_queue ((COALESCE(end_time, skip_time)))
    WHERE job_status IN ('Skip', 'Missed');
CREATE INDEX idx_run_review_pending ON public.run_review (date_created) WHERE status = 'PENDING';
CREATE INDEX idx_run_approval_waiting ON public.run_approval (requested_at) WHERE status = 'PENDING';

-- The platform's rules, on from the start: every platform administrator is told by the bell.
INSERT INTO public.alert_rule (tenant_id, kind, name, severity, threshold, window_minutes, notify_bell, notify_email, recipient_roles)
VALUES
    (NULL, 'SERVICE_UNHEALTHY', 'A service is unhealthy', 'CRITICAL', 2, 60, true, false, 'PLATFORM_ADMIN'),
    (NULL, 'SERVICE_RESTARTED', 'A service restarted', 'WARNING', 1, 30, true, false, 'PLATFORM_ADMIN'),
    (NULL, 'MEMORY_HIGH', 'Memory high-water mark', 'WARNING', 85, 10, true, false, 'PLATFORM_ADMIN'),
    (NULL, 'KAFKA_LAG', 'Kafka consumers falling behind', 'WARNING', 1000, 10, true, false, 'PLATFORM_ADMIN'),
    (NULL, 'DISK_LOW', 'Disk space low', 'CRITICAL', 10, 10, true, false, 'PLATFORM_ADMIN'),
    (NULL, 'BILLING_MISMATCH', 'Billing reconciliation does not agree', 'WARNING', 1, 60, true, false, 'PLATFORM_ADMIN');

-- Row security (MIG-258, V181), as V209: only where V181 has run in this database. alert_rule and alert_incident are
-- tenant tables (a platform row has tenant_id NULL: only an all-tenants session -- a platform administrator, the
-- evaluator -- sees or writes it). service_health_sample and platform_probe are the platform's and hold no workspace.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['alert_rule', 'alert_incident'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('GRANT USAGE, SELECT, UPDATE ON public.%I TO process_app', t || '_seq');
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.service_health_sample TO process_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.platform_probe TO process_app;
    END IF;
END $$;
