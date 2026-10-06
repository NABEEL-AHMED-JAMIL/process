-- MIG-332 (ADR-025): the customer API's inbound work in Core.
--
-- api_idempotency_receipt: every creating POST of /v1 takes an Idempotency-Key (decision 6). One row per workspace, API
--   client and key: the request's fingerprint (sha256 of method, path and body) and, once answered, the answer -- status,
--   body, Location -- replayed for 24 hours to the same key with the same request; another request under the key is a
--   409. A row with no answer yet is a request still being handled. The pattern of worker_callback_receipt (V80), per
--   client instead of per run.
-- api_intake: each run started through the API (POST /v1/pipelines/{id}/runs, or an event route), with the
--   organisation's own reference, the client, the intake file written to the inbox and the file ids it named -- what
--   the read API (MIG-334) answers a run's reference from.
-- event_route: a workspace's own event types (decision 3, "Events in"): an event type starts a pipeline (a job) or a
--   workflow, optionally checked against a data contract first. Generic: nothing names a type.
-- api_event: every event received (POST /v1/events), its type, client and what it started; never its data, which goes
--   to the runs' intake files.
--
-- All four are tenant tables under row security (MIG-258), as V198.

CREATE TABLE public.api_idempotency_receipt (
    tenant_id bigint NOT NULL,
    client_id character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_hash character(64) NOT NULL,
    response_status integer,
    response_body text,
    location character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    answered_at timestamp with time zone,
    CONSTRAINT pk_api_idempotency_receipt PRIMARY KEY (tenant_id, client_id, idempotency_key),
    CONSTRAINT ck_api_idempotency_receipt_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$')
);
COMMENT ON TABLE public.api_idempotency_receipt IS 'MIG-332: the customer API''s Idempotency-Key receipts, per workspace and client: the request''s fingerprint and, once answered, the answer replayed for 24 hours.';
CREATE INDEX idx_api_idempotency_receipt_created ON public.api_idempotency_receipt (tenant_id, client_id, created_at);

CREATE SEQUENCE public.api_intake_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.api_intake (
    intake_id bigint DEFAULT nextval('public.api_intake_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    client_id character varying(64) NOT NULL,
    reference character varying(128),
    event_id bigint,
    input_bucket character varying(255) NOT NULL,
    input_key character varying(1024) NOT NULL,
    file_ids text,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_api_intake PRIMARY KEY (intake_id),
    CONSTRAINT ux_api_intake_run UNIQUE (job_queue_id)
);
COMMENT ON TABLE public.api_intake IS 'MIG-332: a run started through the customer API: the client, the organisation''s reference, the intake file and the file ids it named.';
CREATE INDEX idx_api_intake_tenant ON public.api_intake (tenant_id, intake_id DESC);

CREATE SEQUENCE public.event_route_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.event_route (
    route_id bigint DEFAULT nextval('public.event_route_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    event_type character varying(128) NOT NULL,
    target_kind character varying(16) NOT NULL,
    job_id bigint,
    workflow_key character varying(128),
    contract_id bigint,
    contract_name character varying(255),
    contract_version integer,
    status character varying(12) DEFAULT 'Active' NOT NULL,
    created_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone,
    CONSTRAINT pk_event_route PRIMARY KEY (route_id),
    CONSTRAINT ck_event_route_type CHECK (event_type ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_event_route_kind CHECK ((target_kind = 'PIPELINE' AND job_id IS NOT NULL AND workflow_key IS NULL)
        OR (target_kind = 'WORKFLOW' AND workflow_key IS NOT NULL AND job_id IS NULL)),
    CONSTRAINT ck_event_route_status CHECK (status IN ('Active', 'Inactive')),
    CONSTRAINT ck_event_route_contract CHECK (contract_id IS NULL OR contract_name IS NULL)
);
COMMENT ON TABLE public.event_route IS 'MIG-332: a workspace''s event routes: an event type (the organisation''s own) starts a pipeline (job_id) or a workflow (workflow_key), its data checked against a contract first when one is named.';
CREATE INDEX idx_event_route_type ON public.event_route (tenant_id, event_type);

CREATE SEQUENCE public.api_event_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.api_event (
    event_id bigint DEFAULT nextval('public.api_event_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    event_type character varying(128) NOT NULL,
    client_id character varying(64) NOT NULL,
    started text,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_api_event PRIMARY KEY (event_id)
);
COMMENT ON TABLE public.api_event IS 'MIG-332: an event received through the customer API -- its type, the client, and what it started (never its data).';
CREATE INDEX idx_api_event_tenant ON public.api_event (tenant_id, event_id DESC);

-- Row security (MIG-258, V181), as V198: only where V181 has run in this database.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['api_idempotency_receipt', 'api_intake', 'event_route', 'api_event'] LOOP
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
        END LOOP;
        GRANT USAGE, SELECT, UPDATE ON public.api_intake_seq, public.event_route_seq, public.api_event_seq TO process_app;
    END IF;
END $$;
