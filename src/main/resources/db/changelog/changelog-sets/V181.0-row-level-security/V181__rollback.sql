-- V181's way back: the two functions as Postgres ships them, the four trigger functions the writer's again, every
-- policy V181 made dropped, row security off, and process_app's grants taken back. The role itself is left
-- (roles are the cluster's, not this database's; another database may use it); nothing else changes.
ALTER FUNCTION pg_catalog.timezone(text, timestamp with time zone) NOT LEAKPROOF;
ALTER FUNCTION pg_catalog.date(timestamp without time zone) NOT LEAKPROOF;

DO $$
DECLARE
    f text;
BEGIN
    FOREACH f IN ARRAY ARRAY['scheduler_dispatch_eligible_from_job', 'scheduler_dispatch_eligible_on_write',
        'source_job_assigned_username_on_write', 'source_job_assigned_username_on_rename']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public' AND p.proname = f AND p.pronargs = 0) THEN
            EXECUTE format('ALTER FUNCTION public.%I() SECURITY INVOKER RESET search_path', f);
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    p record;
BEGIN
    FOR p IN
        SELECT c.relname AS tbl, pol.polname AS pol FROM pg_policy pol JOIN pg_class c ON c.oid = pol.polrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND pol.polname IN ('tenant_isolation', 'tenant_read', 'tenant_insert', 'tenant_update', 'tenant_delete')
    LOOP
        EXECUTE format('DROP POLICY %I ON public.%I', p.pol, p.tbl);
    END LOOP;
    FOR p IN
        SELECT c.relname AS tbl, NULL::name AS pol FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND (c.relrowsecurity OR c.relforcerowsecurity)
    LOOP
        EXECUTE format('ALTER TABLE public.%I NO FORCE ROW LEVEL SECURITY', p.tbl);
        EXECUTE format('ALTER TABLE public.%I DISABLE ROW LEVEL SECURITY', p.tbl);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'process_app') THEN
        ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM process_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE USAGE, SELECT, UPDATE ON SEQUENCES FROM process_app;
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM process_app;
        REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM process_app;
        REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM process_app;
        REVOKE ALL ON SCHEMA public FROM process_app;
    END IF;
END $$;
