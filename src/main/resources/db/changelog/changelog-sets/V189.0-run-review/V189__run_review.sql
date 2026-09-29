-- MIG-237: two-party result review of a run. A run's results start PENDING when its pipeline asks for review
-- (settings.review.required in its definition, MIG-230) and are never approved by themselves: each required party --
-- INTERNAL (the workspace's tenant administrators, in the console) and CUSTOMER (the workspace's API clients, MIG-234)
-- -- records one decision, APPROVED or REJECTED. Every required approval makes the run APPROVED; one rejection makes
-- it REJECTED. process.pipeline.review.RunReviewService applies the rules; these tables hold the record.
--
--   run_review_decision: one decision per party per run, with its comment, a rejection's reason and who decided. The
--                        audit trail itself: process_app may read and add a decision, never change or remove one.
--   run_review:          the run's review status as last decided (PENDING, APPROVED, REJECTED), the parties it
--                        required when first decided on, and the run a rejection queued again. A run with no row has
--                        had no decision: its status is worked out from its pipeline's definition (NOT_REQUIRED or
--                        PENDING).
--
-- Why not V180's result_review: that is one party's review of one result_record (a case's result, made by the result
-- producer that comes with the customer intake, MIG-236 -- not built). MIG-237 reviews a run's results as a whole --
-- its manifest (V188) -- which is what a run has today. When a run's review is decided, its PENDING result_records
-- take the run's decision (RunReviewService); their per-result reviews stay V180's.
--
-- Expand-only: two new tables. The rules of process's tables as V180: tenant_id NOT NULL, held to the run's by a
-- composite foreign key and filled from it (V102's tenant_id_from_parent); instants; a party, decision or status
-- column takes only its enum's spellings (V164: ReviewParty, ReviewDecision, RunReviewStatus); the sequence starts at
-- 1000. rerun_job_queue_id and reviewer_user_id are plain bigints: the re-run and the person may go, the record stays.

CREATE SEQUENCE public.run_review_decision_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_review_decision (
    run_review_decision_id bigint DEFAULT nextval('public.run_review_decision_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    attempt integer NOT NULL,
    party character varying(16) NOT NULL,
    decision character varying(16) NOT NULL,
    comment character varying(2000),
    reason character varying(2000),
    reviewer_user_id bigint,
    reviewer_name character varying(255),
    decided_at timestamp with time zone DEFAULT now() NOT NULL,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_run_review_decision PRIMARY KEY (run_review_decision_id),
    -- One decision per party per run: a decision is not taken back, and a rejected run is run again as a new run.
    CONSTRAINT ux_run_review_decision_run_party UNIQUE (job_queue_id, party),
    CONSTRAINT fk_run_review_decision_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_review_decision_attempt CHECK (attempt > 0),
    CONSTRAINT ck_run_review_decision_party_enum CHECK (party IN ('INTERNAL', 'CUSTOMER')),
    CONSTRAINT ck_run_review_decision_decision_enum CHECK (decision IN ('APPROVED', 'REJECTED')),
    -- A rejection says why.
    CONSTRAINT ck_run_review_decision_reason CHECK (decision <> 'REJECTED' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

COMMENT ON TABLE public.run_review_decision IS 'MIG-237: one party''s review of a run''s results -- APPROVED or REJECTED, with a comment, a rejection''s reason and who decided. One per party per run; insert-only for process_app (the audit trail).';
COMMENT ON COLUMN public.run_review_decision.party IS 'INTERNAL = the workspace''s tenant administrators, in the console; CUSTOMER = the workspace''s API clients (MIG-234).';
COMMENT ON COLUMN public.run_review_decision.reviewer_user_id IS 'Who decided: an app_user id for an internal review (a plain id: the person may be removed, the record stays).';

CREATE INDEX idx_run_review_decision_tenant ON public.run_review_decision (tenant_id);

CREATE TRIGGER run_review_decision_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_review_decision
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

CREATE TABLE public.run_review (
    job_queue_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    required_parties character varying(32) NOT NULL,
    status character varying(16) NOT NULL,
    decided_at timestamp with time zone,
    rerun_job_queue_id bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_review PRIMARY KEY (job_queue_id),
    CONSTRAINT fk_run_review_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_review_status_enum CHECK (status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_run_review_required CHECK (required_parties IN ('', 'INTERNAL', 'CUSTOMER', 'INTERNAL,CUSTOMER')),
    -- Nothing required is the one way to NOT_REQUIRED: a run that asks for review is never approved without it.
    CONSTRAINT ck_run_review_not_required CHECK ((required_parties = '') = (status = 'NOT_REQUIRED')),
    CONSTRAINT ck_run_review_rerun CHECK (rerun_job_queue_id IS NULL OR status = 'REJECTED')
);

COMMENT ON TABLE public.run_review IS 'MIG-237: a run''s review status as last decided, the parties its pipeline required, and the run a rejection queued again. No row: no decision yet.';

CREATE INDEX idx_run_review_tenant ON public.run_review (tenant_id);

CREATE TRIGGER run_review_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_review
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181): tenant tables, so process_app sees and writes the session's workspace only -- the
-- policy V181 gives every tenant table, ENABLE and FORCE. Only where V181 has run in this database, as V186 and V188
-- do: on a database V181 has not reached yet, V181's own loop covers these tables. run_review_decision is insert-only for
-- process_app (SELECT, INSERT): a decision is the audit trail, and nothing in the application changes or removes one;
-- run_review is read, added and moved on (SELECT, INSERT, UPDATE), never removed. Both go with their run. V181's default
-- privileges have already given process_app every table's four rights, so what it must not have is taken back here.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON public.run_review_decision FROM process_app;
        REVOKE DELETE, TRUNCATE ON public.run_review FROM process_app;
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_review_decision') THEN
            GRANT SELECT, INSERT ON public.run_review_decision TO process_app;
            GRANT USAGE, SELECT ON public.run_review_decision_seq TO process_app;
            ALTER TABLE public.run_review_decision ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.run_review_decision FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.run_review_decision TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_review') THEN
            GRANT SELECT, INSERT, UPDATE ON public.run_review TO process_app;
            ALTER TABLE public.run_review ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.run_review FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.run_review TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
