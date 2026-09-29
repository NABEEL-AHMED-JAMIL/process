-- MIG-231: the Task Registry's per-workspace switches.
--
-- The registry's entries -- each task's name, kind, schemas, backing service, retry, timeout, role, default and AI
-- tool name -- are code (process.pipeline.registry.TaskSpec, one per StepTask bean): a task's schema and its
-- implementation change together, and no row can describe a task the running build does not have. What a workspace
-- decides is only whether an overridable task is on for it: one row here per task it has switched against the task's
-- default. No row = the default. The legacy task is not overridable (every existing pipeline stays runnable); the
-- application refuses a row for it, and a row for a code no longer registered is ignored.
--
-- Expand-only: one new table. The rules of process's tables as V180: tenant_id NOT NULL (the workspace is Identity's,
-- another database, so a plain bigint -- V161 demoted Identity foreign keys), instants, the sequence starts at 1000.

CREATE SEQUENCE public.task_registry_override_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.task_registry_override (
    task_registry_override_id bigint DEFAULT nextval('public.task_registry_override_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    task_code character varying(64) NOT NULL,
    enabled boolean NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_task_registry_override PRIMARY KEY (task_registry_override_id),
    -- One switch per task per workspace: saving again updates it.
    CONSTRAINT ux_task_registry_override_task UNIQUE (tenant_id, task_code),
    -- A task code as StepTasks registers it: lower case letters, digits and '_'.
    CONSTRAINT ck_task_registry_override_code CHECK (task_code ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT ck_task_registry_override_not_legacy CHECK (task_code <> 'legacy'),
    CONSTRAINT ck_task_registry_override_tenant CHECK (tenant_id > 0)
);

COMMENT ON TABLE public.task_registry_override IS 'A workspace''s switch on a registered pipeline task (MIG-231): enabled or not, against the task''s default (code). No row = the default.';
COMMENT ON COLUMN public.task_registry_override.task_code IS 'process.pipeline.StepTask.code(): read_api, write_database, ...';

-- Row security (MIG-258, V181): a tenant table, so process_app sees and writes the session's workspace only -- the
-- policy V181 gives every tenant table (platform-commons RowSecurity.OWNED_ROWS), ENABLE and FORCE. Only where V181 has
-- run in this database, as V182 and V183 do: on a database V181 has not reached yet, V181's own loop covers this table.
DO $$
DECLARE
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    IF EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '181.0-row-level-security')
       AND NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'task_registry_override') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.task_registry_override TO process_app;
        GRANT USAGE, SELECT, UPDATE ON public.task_registry_override_seq TO process_app;
        ALTER TABLE public.task_registry_override ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.task_registry_override FORCE ROW LEVEL SECURITY;
        EXECUTE format('CREATE POLICY tenant_isolation ON public.task_registry_override TO process_app USING (%s) WITH CHECK (%s)', owned, owned);
    END IF;
END $$;
