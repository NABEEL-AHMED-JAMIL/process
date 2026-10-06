-- MIG-334: GET /v1/runs is a workspace's runs newest first, a keyset page at a time (job_queue_id < cursor ORDER BY
-- job_queue_id DESC). idx_job_queue_tenant finds the workspace's rows but not in run order; this reads the page alone.
-- CONCURRENTLY, outside a transaction (runInTransaction: false): job_queue takes inserts every dispatch cycle.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_tenant_run ON public.job_queue (tenant_id, job_queue_id);
