-- V200 rollback: the two indexes only; no data changes.
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_run_day;
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_job_id_run;
