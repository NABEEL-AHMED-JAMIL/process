-- Owner #16 (2026-10-19): a platform administrator enables, disables or blocks a Task Registry task per workspace -- the
-- tools' model (ai-service V5.13, owner #15) for pipeline step tasks. Expand-only: two new tables and one nullable column.
--
--   task_registry_override.updated_by_role: who set the workspace's switch -- TENANT_ADMIN, or PLATFORM_ADMIN when the
--     platform set it for the workspace; null on rows written before.
--   task_registry_block: a platform administrator's block of one task for one workspace. A blocked task cannot be added
--     to a pipeline (validate and save refuse it), a run whose definition names it is declined naming the task, and the
--     workspace's administrator cannot switch it on. No row: not blocked. Lifting a block leaves the switch as it was.
--   task_registry_audit: every switch, block and reset of a task in a workspace, by whom (and as which role), with the
--     state before and after and the reason given. Insert-only for process_app.
--
-- The tasks themselves stay code (TaskSpec): the platform default is each task's enabledByDefault. Rules of process's
-- tables as V186: tenant_id NOT NULL and > 0, instants, sequences start at 1000, legacy is never switched or blocked.

ALTER TABLE public.task_registry_override ADD COLUMN updated_by_role character varying(32);
COMMENT ON COLUMN public.task_registry_override.updated_by_role IS 'Owner #16: TENANT_ADMIN or PLATFORM_ADMIN -- who set the switch; null on rows written before.';

CREATE TABLE public.task_registry_block (
    tenant_id bigint NOT NULL,
    task_code character varying(64) NOT NULL,
    reason character varying(500),
    blocked_by bigint NOT NULL,
    date_blocked timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_task_registry_block PRIMARY KEY (tenant_id, task_code),
    CONSTRAINT ck_task_registry_block_code CHECK (task_code ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT ck_task_registry_block_not_legacy CHECK (task_code <> 'legacy'),
    CONSTRAINT ck_task_registry_block_tenant CHECK (tenant_id > 0)
);
COMMENT ON TABLE public.task_registry_block IS 'Owner #16: a platform administrator''s block of one registry task for one workspace; the workspace cannot switch it on and its pipelines cannot use it. No row: not blocked.';

CREATE SEQUENCE public.task_registry_audit_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE public.task_registry_audit (
    audit_id bigint DEFAULT nextval('public.task_registry_audit_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    task_code character varying(64) NOT NULL,
    action character varying(16) NOT NULL,
    before_value character varying(16),
    after_value character varying(16),
    note character varying(500),
    actor_user_id bigint,
    actor_role character varying(32),
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_task_registry_audit PRIMARY KEY (audit_id),
    CONSTRAINT ck_task_registry_audit_action CHECK (action IN ('enable', 'disable', 'default', 'block')),
    CONSTRAINT ck_task_registry_audit_tenant CHECK (tenant_id > 0)
);
CREATE INDEX idx_task_registry_audit_task ON public.task_registry_audit USING btree (tenant_id, task_code, audit_id DESC);
COMMENT ON TABLE public.task_registry_audit IS 'Owner #16: who switched, blocked or reset a registry task in a workspace, and how; insert-only for process_app.';

-- Row security (MIG-258, V181): both are tenant tables -- the policy V181 gives every tenant table, ENABLE and FORCE --
-- only where V181 has run in this database (on one it has not reached yet, V181's own loop covers them).
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
    t text;
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.task_registry_block TO process_app;
        -- Insert-only: V181's default privileges gave process_app every table's UPDATE and DELETE; the audit keeps neither.
        REVOKE UPDATE, DELETE, TRUNCATE ON public.task_registry_audit FROM process_app;
        GRANT SELECT, INSERT ON public.task_registry_audit TO process_app;
        GRANT USAGE, SELECT ON public.task_registry_audit_seq TO process_app;
        FOREACH t IN ARRAY ARRAY['task_registry_block', 'task_registry_audit'] LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = t) THEN
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            END IF;
        END LOOP;
    END IF;
END $$;
