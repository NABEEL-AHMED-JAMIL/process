-- Bulk review (2026-10-12). A tenant administrator approves or rejects many runs at once (sourceJob.json/review/bulkDecide),
-- or a reviewer acts on many Task inbox tasks at once (workflow-service's bulkAct); each item is still decided on its own,
-- by the rules of a single decision, and its row names the bulk decision it was part of:
--   run_review_decision.bulk_id: the bulk decision a run's review decision was part of; NULL for a single decision.
--   run_approval.bulk_id: the same for an Approval step's request decided in the Task inbox.
--   review_policy: a workspace's "Allow bulk approve" for pipeline runs. No row means on (the default); bulk reject is
--   always allowed. A pipeline that requires individual review (settings.review.individual) is never bulk-decided.
--
-- Expand-only: two nullable columns (no rewrite, run_review_decision stays insert-only for process_app) and one new tenant
-- table under row security (V181), as V198's form_share_policy.

ALTER TABLE public.run_review_decision ADD COLUMN bulk_id character varying(64);
COMMENT ON COLUMN public.run_review_decision.bulk_id IS 'Bulk review: the bulk decision this decision was part of (the client''s id); NULL for a single decision.';

ALTER TABLE public.run_approval ADD COLUMN bulk_id character varying(64);
COMMENT ON COLUMN public.run_approval.bulk_id IS 'Bulk review: the bulk decision in the Task inbox this request was decided in; NULL otherwise.';

CREATE TABLE public.review_policy (
    tenant_id bigint NOT NULL,
    bulk_approve_runs boolean DEFAULT true NOT NULL,
    updated_by bigint,
    date_updated timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_review_policy PRIMARY KEY (tenant_id)
);
COMMENT ON TABLE public.review_policy IS 'Bulk review: whether a workspace lets its pipeline runs be approved in bulk (on unless an administrator turns it off; bulk reject is always allowed).';

-- Row security (MIG-258, V181), as V198: only where V181 has run in this database.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'review_policy') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.review_policy TO process_app;
            ALTER TABLE public.review_policy ENABLE ROW LEVEL SECURITY;
            ALTER TABLE public.review_policy FORCE ROW LEVEL SECURITY;
            EXECUTE format('CREATE POLICY tenant_isolation ON public.review_policy TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
        END IF;
    END IF;
END $$;
