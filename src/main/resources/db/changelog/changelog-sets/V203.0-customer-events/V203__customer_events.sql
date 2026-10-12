-- MIG-333 (ADR-025 decision 9): the customer API's events out, Core's half. Expand-only: one table, three triggers.
--
-- api_event_out: the journal of what happened to a workspace's runs, files and form submissions that a customer may be
--   told about -- a run reached Running, Completed or Failed; a run's step made a file; a party decided a run's review;
--   a form submission settled. Written in the transaction of the change itself (the triggers below, and the review
--   service for a decision), so nothing is announced that did not commit and nothing that committed is missed, whichever
--   of the many paths moved a run (the engine, a worker's callback, the stall sweep, an operator). A run row carries the
--   run as it stood at that moment (status, status line, times, attempt), so run.started says "running" even when the
--   run has finished by the time it is relayed.
--   CustomerEventRelay (one instance at a time, ShedLock) reads the rows not yet published in order, builds each event
--   in the customer API's shapes (the envelope {id, type, createdAt, workspaceId, data}, data what GET of the resource
--   answers) and writes it to platform_outbox for platform.customer.events.v1 in the same transaction that stamps
--   published_at. integration-service delivers them to the workspace's webhooks; nothing reads this table but the relay.
--
-- Tenant table under row security (MIG-258) as V201. The triggers run as the session that changed the row, so the
-- journal row is written into the same workspace the changed row belongs to.

CREATE SEQUENCE public.api_event_out_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.api_event_out (
    out_id bigint DEFAULT nextval('public.api_event_out_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    kind character varying(24) NOT NULL,
    job_queue_id bigint,
    job_id bigint,
    run_output_id bigint,
    submission_id bigint,
    job_status character varying(16),
    status_message text,
    run_created_at timestamp with time zone,
    started_at timestamp with time zone,
    ended_at timestamp with time zone,
    attempt integer,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    event_count integer,
    CONSTRAINT pk_api_event_out PRIMARY KEY (out_id),
    CONSTRAINT ck_api_event_out_kind CHECK (kind IN ('run_status', 'file_made', 'review_decided', 'submission'))
);
COMMENT ON TABLE public.api_event_out IS 'MIG-333: what happened that a customer may be told about (run status, a made file, a review decision, a settled form submission), written with the change; CustomerEventRelay publishes each to platform.customer.events.v1.';
CREATE INDEX idx_api_event_out_pending ON public.api_event_out (out_id) WHERE published_at IS NULL;
CREATE INDEX idx_api_event_out_published ON public.api_event_out (published_at) WHERE published_at IS NOT NULL;

-- A run's status: only the three a customer is told about, and only when it changes (a worker's Running heartbeat is not
-- a change). An insert counts too: a run born Failed (a dispatch refusal) is a failed run.
CREATE OR REPLACE FUNCTION public.api_event_out_run_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.job_status IN ('Running', 'Completed', 'Failed')
        AND (TG_OP = 'INSERT' OR OLD.job_status IS DISTINCT FROM NEW.job_status) THEN
        INSERT INTO public.api_event_out (tenant_id, kind, job_queue_id, job_id, job_status, status_message, run_created_at,
            started_at, ended_at, attempt)
        VALUES (NEW.tenant_id, 'run_status', NEW.job_queue_id, NEW.job_id, NEW.job_status, NEW.job_status_message,
            NEW.date_created, NEW.start_time, COALESCE(NEW.end_time, NEW.skip_time), NEW.attempt);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_api_event_out_run_status AFTER INSERT OR UPDATE OF job_status ON public.job_queue
    FOR EACH ROW EXECUTE FUNCTION public.api_event_out_run_status();

-- A made file: a new run_output row with a file id, or a later try's file replacing an earlier one's (a new file id).
-- A file id given late to a row recorded before MIG-334 (OLD.file_id null on an update) is not news.
CREATE OR REPLACE FUNCTION public.api_event_out_file_made() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.file_id IS NOT NULL AND (TG_OP = 'INSERT' OR (OLD.file_id IS NOT NULL AND OLD.file_id <> NEW.file_id)) THEN
        INSERT INTO public.api_event_out (tenant_id, kind, run_output_id) VALUES (NEW.tenant_id, 'file_made', NEW.run_output_id);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_api_event_out_file_made AFTER INSERT OR UPDATE OF file_id ON public.run_output
    FOR EACH ROW EXECUTE FUNCTION public.api_event_out_file_made();

-- A settled form submission: one that starts no run is settled when received; one that starts a run, when the run
-- started or could not (Received -> RunStarted / RunNotStarted), so the event can name the run.
CREATE OR REPLACE FUNCTION public.api_event_out_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.job_id IS NULL)
        OR (TG_OP = 'UPDATE' AND OLD.status = 'Received' AND NEW.status IN ('RunStarted', 'RunNotStarted')) THEN
        INSERT INTO public.api_event_out (tenant_id, kind, submission_id, job_queue_id) VALUES (NEW.tenant_id, 'submission',
            NEW.submission_id, NEW.job_queue_id);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_api_event_out_submission AFTER INSERT OR UPDATE OF status ON public.form_submission
    FOR EACH ROW EXECUTE FUNCTION public.api_event_out_submission();

-- Row security (MIG-258, V181), as V201: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.api_event_out TO process_app;
        ALTER TABLE public.api_event_out ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.api_event_out FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.api_event_out TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        GRANT USAGE, SELECT, UPDATE ON public.api_event_out_seq TO process_app;
    END IF;
END $$;
