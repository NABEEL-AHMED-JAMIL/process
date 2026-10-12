-- MIG-346: an Approval step anywhere in a pipeline. When a run reaches one, the run stays Running (it is in flight: its
-- job runs nothing else meanwhile) with its step waiting, and one run_approval row is the request -- the Task inbox task
-- workflow-service makes from Core's run-approval.requested event. The reviewers' decision comes back on
-- workflow-service's instance-changed event: APPROVED continues the run from the next step, REJECTED fails it with the
-- reason. A request nobody decides by expires_at is EXPIRED (the run fails); one whose run was closed meanwhile (an
-- operator failed or interrupted it) is CANCELLED, and its inbox task is closed.
--
-- Expand-only: one new tenant table, tenant_id filled from the run (V102's tenant_id_from_parent), under row security
-- (V181), as V205's.

CREATE SEQUENCE public.run_approval_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_approval (
    run_approval_id bigint DEFAULT nextval('public.run_approval_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    attempt integer NOT NULL,
    step_execution_id bigint NOT NULL,
    step_index integer NOT NULL,
    step_key character varying(64) NOT NULL,
    status character varying(16) DEFAULT 'PENDING' NOT NULL,
    title character varying(255) NOT NULL,
    reviewers_kind character varying(16) NOT NULL,
    reviewers_value character varying(64) NOT NULL,
    records_in bigint,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    decided_at timestamp with time zone,
    decided_by_user_id bigint,
    decided_by_name character varying(255),
    comment character varying(2000),
    workflow_instance_id bigint,
    workflow_task_id bigint,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_approval PRIMARY KEY (run_approval_id),
    CONSTRAINT ux_run_approval_step UNIQUE (step_execution_id),
    CONSTRAINT fk_run_approval_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_run_approval_step FOREIGN KEY (step_execution_id)
        REFERENCES public.step_execution (step_execution_id) ON DELETE CASCADE,
    CONSTRAINT ck_run_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'group')),
    CONSTRAINT ck_run_approval_decided CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_run_approval_attempt CHECK (attempt > 0 AND step_index >= 0)
);

COMMENT ON TABLE public.run_approval IS 'MIG-346: an Approval step''s request: the run waits (Running) until the reviewers approve (it continues) or reject (it fails) in the Task inbox; EXPIRED past expires_at, CANCELLED when the run was closed meanwhile.';

CREATE INDEX idx_run_approval_tenant ON public.run_approval (tenant_id);
CREATE INDEX idx_run_approval_run ON public.run_approval (job_queue_id);
-- The sweep's and the stall sweep's question: which requests still wait.
CREATE INDEX idx_run_approval_pending ON public.run_approval (expires_at) WHERE status = 'PENDING';

CREATE TRIGGER run_approval_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.run_approval
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- Row security (MIG-258, V181), as V205: only where V181 has run in this database.
DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        FOREACH t IN ARRAY ARRAY['run_approval'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO process_app', t);
                EXECUTE format('GRANT USAGE, SELECT, UPDATE ON public.%I TO process_app', t || '_seq');
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
