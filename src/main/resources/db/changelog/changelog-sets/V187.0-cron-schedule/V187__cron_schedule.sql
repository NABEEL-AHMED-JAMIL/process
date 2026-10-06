-- Wave 4: the Cron frequency. A schedule may repeat on its own cron expression -- five fields (minute hour
-- day-of-month month day-of-week), or six with a leading seconds field of 0 -- instead of an interval. The application
-- checks the expression when a schedule is saved (process.util.CronSchedule) and steps it in Chicago wall-clock like
-- every other frequency; next_run_at stays the instant it has been since V100.
--
-- Expand-only: one nullable column on scheduler. Every existing row keeps its frequency and reads null here. scheduler
-- is already a tenant table under V181's tenant_isolation policy, which covers a new column as it stands; nothing is
-- granted or guarded anew. The frequency column is free text (no CHECK names its values), so 'Cron' needs no change.

ALTER TABLE public.scheduler ADD COLUMN cron_expression character varying(120);

COMMENT ON COLUMN public.scheduler.cron_expression IS 'A Cron schedule''s expression (frequency = ''Cron''): five fields, or six with seconds 0, Chicago wall-clock; null for every other frequency. Wave 4.';
