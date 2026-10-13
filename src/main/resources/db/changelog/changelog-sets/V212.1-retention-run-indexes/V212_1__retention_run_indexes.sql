-- V212.1, MIG-354: the retention pass's reads of a workspace's runs, oldest first, among those whose outputs (or logs)
-- are still there. Partial: a run the policy has done with leaves the index, so a daily batch never walks past the runs
-- earlier passes removed.
--
-- CONCURRENTLY, outside a transaction (runInTransaction: false): job_queue takes inserts every dispatch cycle.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_retention_outputs ON public.job_queue (tenant_id, date_created)
    WHERE outputs_removed_at IS NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_retention_logs ON public.job_queue (tenant_id, date_created)
    WHERE logs_removed_at IS NULL;
