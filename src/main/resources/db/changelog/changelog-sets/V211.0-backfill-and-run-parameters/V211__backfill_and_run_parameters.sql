-- MIG-347: backfill and run parameters (2026-10-13).
--
--   job_queue.run_params: a run's parameter values and its run.* values ({"params": {...}, "run": {"date", "windowStart",
--     "windowEnd", "zone"}}), normalised JSON text: set by "Run with..." and by a backfill; null for every other run, whose
--     values are its pipeline's defaults and whose run.date the step engine works out (its slot's date, else today).
--   job_queue.run_slot_at: the period a run is for -- a scheduled run's slot, a backfill run's period start. Null for a
--     run started by hand, a file, a form, a document or the API. "A date already has a completed run" reads it.
--   job_queue.run_lane: which in-flight lane the run holds. 0 is the job's own lane (Run now, schedules, inbox, forms,
--     documents, the API): still one run in flight at a time there (P12, V83). 1 to 4 are backfill lanes: a backfill of
--     concurrency N runs at most N of the job's past dates at once, each in a lane of its own, beside the job's live runs.
--   job_queue.backfill_id: the backfill a run is part of (every attempt and every retry of its date); null otherwise.
--   ux_job_queue_one_in_flight_per_job: one in-flight run per job AND LANE -- the same predicate as V83 (the in-flight
--     set, JobStatusInFlightTest), keyed (job_id, run_lane), so lane 0 keeps V83's rule unchanged.
--   backfill: one request to run a schedule for each period of a past range -- its step (DAILY, HOURLY, WEEKLY), the
--     workspace's billing zone the periods are counted in, its concurrency, whether completed periods are run again, the
--     parameter values, its cost estimate, and its state (RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED).
--   backfill_slot: one row per period, with the run made for it (the latest one, when it was retried) and its state
--     (PENDING, QUEUED, COMPLETED, FAILED, SKIPPED, CANCELLED). ux_backfill_slot_open_per_job: one open period per job and
--     period start across every backfill -- the idempotency per (job, date).
--
-- Expand-only, but for the one index rebuilt on a wider key (its predicate unchanged).

ALTER TABLE public.job_queue ADD COLUMN run_params text;
ALTER TABLE public.job_queue ADD COLUMN run_slot_at timestamp with time zone;
ALTER TABLE public.job_queue ADD COLUMN run_lane smallint DEFAULT 0 NOT NULL;
ALTER TABLE public.job_queue ADD COLUMN backfill_id bigint;
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_run_lane CHECK (run_lane BETWEEN 0 AND 4);
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_lane_backfill CHECK (run_lane = 0 OR backfill_id IS NOT NULL);
COMMENT ON COLUMN public.job_queue.run_params IS 'MIG-347: the run''s parameter values and run.* values as normalised JSON; null: the pipeline''s defaults.';
COMMENT ON COLUMN public.job_queue.run_slot_at IS 'MIG-347: the period the run is for: a scheduled run''s slot, a backfill run''s period start.';
COMMENT ON COLUMN public.job_queue.run_lane IS 'MIG-347: 0 the job''s own lane (one run in flight); 1-4 a backfill''s lanes.';
COMMENT ON COLUMN public.job_queue.backfill_id IS 'MIG-347: the backfill this run is part of.';

DROP INDEX public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id, run_lane)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING');
-- "Does this period already have a completed run?": the job's completed runs by the period they were for.
CREATE INDEX idx_job_queue_slot_completed ON public.job_queue (job_id, run_slot_at)
    WHERE run_slot_at IS NOT NULL AND UPPER(job_status) = 'COMPLETED';
CREATE INDEX idx_job_queue_backfill ON public.job_queue (backfill_id) WHERE backfill_id IS NOT NULL;

CREATE SEQUENCE public.backfill_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE public.backfill (
    backfill_id bigint DEFAULT nextval('public.backfill_seq') NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    status character varying(16) DEFAULT 'RUNNING' NOT NULL,
    step character varying(8) NOT NULL,
    range_from date NOT NULL,
    range_to date NOT NULL,
    zone character varying(64) NOT NULL,
    concurrency smallint NOT NULL,
    rerun_completed boolean DEFAULT false NOT NULL,
    params text,
    total integer NOT NULL,
    estimate_per_run numeric(16,5),
    estimate_total numeric(16,5),
    estimate_currency character varying(3),
    estimate_basis character varying(300),
    hold_reason character varying(300),
    created_by bigint,
    created_via character varying(8) DEFAULT 'console' NOT NULL,
    client_id character varying(64),
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    ended_at timestamp with time zone,
    paused_at timestamp with time zone,
    paused_by bigint,
    cancelled_by bigint,
    date_updated timestamp with time zone,
    CONSTRAINT pk_backfill PRIMARY KEY (backfill_id),
    CONSTRAINT ck_backfill_status CHECK (status IN ('RUNNING', 'PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_backfill_step CHECK (step IN ('DAILY', 'HOURLY', 'WEEKLY')),
    CONSTRAINT ck_backfill_range CHECK (range_to >= range_from),
    CONSTRAINT ck_backfill_concurrency CHECK (concurrency BETWEEN 1 AND 4),
    CONSTRAINT ck_backfill_total CHECK (total BETWEEN 1 AND 366),
    CONSTRAINT ck_backfill_via CHECK (created_via IN ('console', 'api')),
    CONSTRAINT ck_backfill_ended CHECK ((status IN ('CANCELLED', 'COMPLETED', 'FAILED')) = (ended_at IS NOT NULL)),
    CONSTRAINT fk_backfill_job_tenant FOREIGN KEY (job_id, tenant_id) REFERENCES public.source_job (job_id, tenant_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX idx_backfill_open ON public.backfill (backfill_id) WHERE status IN ('RUNNING', 'PAUSED');
CREATE INDEX idx_backfill_tenant ON public.backfill (tenant_id, backfill_id DESC);
CREATE INDEX idx_backfill_job ON public.backfill (job_id, backfill_id DESC);
COMMENT ON TABLE public.backfill IS 'MIG-347: a schedule run once for each period (day, hour or week) of a past range, a few at a time.';
COMMENT ON COLUMN public.backfill.zone IS 'The workspace''s billing time zone when it was made: the periods are its days and hours.';
COMMENT ON COLUMN public.backfill.hold_reason IS 'Why a RUNNING backfill queues nothing just now (its workspace is paused, its schedule is off); null when it may.';
COMMENT ON COLUMN public.backfill.estimate_per_run IS 'The pipeline''s average run cost when it was made (billing''s per-run shares over the last 30 days).';

CREATE TABLE public.backfill_slot (
    backfill_id bigint NOT NULL,
    slot_start timestamp with time zone NOT NULL,
    slot_end timestamp with time zone NOT NULL,
    run_date date NOT NULL,
    tenant_id bigint NOT NULL,
    job_id bigint NOT NULL,
    state character varying(16) DEFAULT 'PENDING' NOT NULL,
    job_queue_id bigint,
    tries smallint DEFAULT 0 NOT NULL,
    note character varying(500),
    date_updated timestamp with time zone,
    CONSTRAINT pk_backfill_slot PRIMARY KEY (backfill_id, slot_start),
    CONSTRAINT fk_backfill_slot_backfill FOREIGN KEY (backfill_id) REFERENCES public.backfill (backfill_id) ON DELETE CASCADE,
    CONSTRAINT ck_backfill_slot_state CHECK (state IN ('PENDING', 'QUEUED', 'COMPLETED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_backfill_slot_period CHECK (slot_end > slot_start),
    CONSTRAINT ck_backfill_slot_run CHECK (state NOT IN ('QUEUED', 'COMPLETED', 'FAILED') OR job_queue_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_backfill_slot_open_per_job ON public.backfill_slot (job_id, slot_start) WHERE state IN ('PENDING', 'QUEUED');
CREATE INDEX idx_backfill_slot_run ON public.backfill_slot (job_queue_id) WHERE job_queue_id IS NOT NULL;
CREATE INDEX idx_backfill_slot_state ON public.backfill_slot (backfill_id, state, slot_start);
-- The dispatcher's each tick: the periods whose run may have ended.
CREATE INDEX idx_backfill_slot_queued ON public.backfill_slot (job_queue_id) WHERE state = 'QUEUED';
COMMENT ON TABLE public.backfill_slot IS 'MIG-347: one period of a backfill and the run made for it; one open period per job and start across backfills.';

-- Row security (MIG-258, V181), as V210 and every tenant table: only where V181 has run in this database. Both are tenant
-- tables; tenant_id is never NULL here, so the policy's NULL arm (the platform's rows elsewhere) admits nothing more.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['backfill', 'backfill_slot'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
        GRANT USAGE, SELECT, UPDATE ON public.backfill_seq TO process_app;
    END IF;
END $$;
