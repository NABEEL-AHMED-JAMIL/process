-- MIG-258: Postgres row-level security on every tenant table of etl_job, as defence in depth behind the tenant
-- filter, TenantScope and the scoped repository methods. A query that forgets its tenant answers the caller's
-- workspace and nothing else; one run with no caller answers nothing.
--
-- Core connects as a superuser, which row security never applies to. So the application works as process_app, a
-- role without login: the datasource's connection-init-sql says SET ROLE process_app (application-*.properties,
-- PROCESS_DB_SESSION_SQL), while Liquibase keeps the login on a connection of its own. process_app is given what
-- the application does -- read and write rows, use sequences -- and owns nothing.
--
-- Each tenant table admits process_app to a row only when the session setting app.tenant_id names its tenant, or
-- app.all_tenants is 'on' (each setting read once per statement in a sub-select, the flag as a range on tenant_id, so
-- the queries keep their plans -- platform-commons' RowSecurity.OWNED_ROWS) (platform-commons' RowSecurityDataSource sets both before each statement, from the caller:
-- the token's workspace; every one for a platform administrator or an @AcrossTenants path; the run's for a worker's
-- callback; nothing otherwise). WITH CHECK holds writes to the same rule.
--
-- Five tables keep platform rows (tenant_id NULL) that every workspace reads -- the (tenant_id = :t OR tenant_id IS
-- NULL) rule (MIG-166's business rules): kafka_connection_profile (the platform default), source_task_type,
-- storage_connection, lookup_data, and user_directory (the platform administrators' names on a workspace's rows).
-- There a workspace may READ a platform row and never write one. Every other tenant table's NULL-tenant rows are
-- the platform's alone.
--
-- The table list is read from the catalogue, so a database that still holds a table a fresh build no longer makes
-- (the moved-out *_moved_mig* tables) is covered too. RowSecurityPostgresTest fails on any tenant table left out.
-- FORCE is set as everywhere else; the login that owns the tables is a superuser and passes regardless. Foreign-key
-- checks and cascades are Postgres's and bypass row security by design; COPY FROM is refused on these tables.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'process_app') THEN
        CREATE ROLE process_app NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO process_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO process_app;
REVOKE ALL ON public.databasechangelog, public.databasechangeloglock FROM process_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO process_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO process_app;
-- What later changesets create, process_app may use the same way (they run as this same login).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO process_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO process_app;

DO $$
DECLARE
    t text;
    owned text := 'tenant_id = (SELECT NULLIF(current_setting(''app.tenant_id'', true), '''')::bigint)'
        || ' OR tenant_id >= (SELECT CASE WHEN current_setting(''app.all_tenants'', true) = ''on'' THEN -9223372036854775808 END)'
        || ' OR (tenant_id IS NULL AND (SELECT current_setting(''app.all_tenants'', true) = ''on''))';
BEGIN
    FOR t IN
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
           AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id'
                        AND NOT a.attisdropped AND a.attnum > 0)
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        IF t IN ('kafka_connection_profile', 'source_task_type', 'storage_connection', 'lookup_data', 'user_directory') THEN
            EXECUTE format('CREATE POLICY tenant_read ON public.%I FOR SELECT TO process_app USING (tenant_id IS NULL OR %s)', t, owned);
            EXECUTE format('CREATE POLICY tenant_insert ON public.%I FOR INSERT TO process_app WITH CHECK (%s)', t, owned);
            EXECUTE format('CREATE POLICY tenant_update ON public.%I FOR UPDATE TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
            EXECUTE format('CREATE POLICY tenant_delete ON public.%I FOR DELETE TO process_app USING (%s)', t, owned);
        ELSE
            EXECUTE format('CREATE POLICY tenant_isolation ON public.%I TO process_app USING (%s) WITH CHECK (%s)', t, owned, owned);
        END IF;
    END LOOP;
END $$;

-- Row security evaluates the policy before any condition that is not LEAKPROOF, so such a condition can no longer be
-- an index condition. The Dashboard's per-day reads filter on (date_created AT TIME ZONE 'America/Chicago')::date,
-- which idx_job_queue_date_created_day serves (MIG-62) -- and which, as process_app, fell back to a nested loop over
-- every run of every job. The two functions in that expression are marked LEAKPROOF in etl_job: timezone(text,
-- timestamptz) can fail only on its zone argument, a literal in every query of Core's; date(timestamp) cannot fail. The
-- guarantee LEAKPROOF gives up -- that SQL written against the policy cannot learn a hidden row from an error -- is
-- not one this policy offers anyway: the settings it reads are the session's own. pg_catalog is not dumped, so a
-- restored etl_job loses this silently: re-run these two lines after a pg_dump/restore (DashboardIndexPostgresTest
-- proves the plan on a fresh build).
ALTER FUNCTION pg_catalog.timezone(text, timestamp with time zone) LEAKPROOF;
ALTER FUNCTION pg_catalog.date(timestamp without time zone) LEAKPROOF;

-- Four trigger functions keep a derived column in step with another table: scheduler.dispatch_eligible from its job
-- (V84), and source_job.assigned_username from app_user (V85). As the writer, under row security, they would read or
-- write the other table as the session sees it -- and a platform administrator assigned to a workspace's job, whose
-- app_user row has no tenant, reads as nobody: the name would be cleared, silently; a scheduler whose job the session
-- cannot see would be marked not eligible, silently. They run as their owner instead (the login that runs this
-- changelog), touching only the rows keyed by the row being written. tenant_id_from_parent (V102) stays the writer's:
-- a child filed under a parent the session cannot see must fail, and it does.
DO $$
DECLARE
    f text;
BEGIN
    FOREACH f IN ARRAY ARRAY['scheduler_dispatch_eligible_from_job', 'scheduler_dispatch_eligible_on_write',
        'source_job_assigned_username_on_write', 'source_job_assigned_username_on_rename']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public' AND p.proname = f AND p.pronargs = 0) THEN
            EXECUTE format('ALTER FUNCTION public.%I() SECURITY DEFINER SET search_path = public, pg_temp', f);
        END IF;
    END LOOP;
END $$;
