-- V202.1 rollback: the index only; no data changes.
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_tenant_run;
