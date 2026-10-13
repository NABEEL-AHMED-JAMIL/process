-- V212.1 rollback: the two indexes only.
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_retention_logs;
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_retention_outputs;
