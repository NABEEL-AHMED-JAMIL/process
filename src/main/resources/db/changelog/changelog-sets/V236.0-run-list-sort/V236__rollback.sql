-- V236 rollback: the four indexes only; no data changes.
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_job_duration;
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_job_ended;
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_job_started;
DROP INDEX CONCURRENTLY IF EXISTS public.idx_job_queue_job_queued;
