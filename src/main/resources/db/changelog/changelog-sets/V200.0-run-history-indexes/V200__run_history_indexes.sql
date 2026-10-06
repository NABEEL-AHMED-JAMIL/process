-- V200, scale review P0 #1 and #2: the two reads that walked a job's whole history get an index each.
--
-- (job_id, job_queue_id): a job's runs newest first, a window at a time (JobQueueRepository.findRecentByJobId,
-- RunReviewService's max(), the assistant). idx_job_queue_job_id finds a job's rows but not in run order, so the
-- newest 50 of a minute job's 525,000 meant fetching and sorting all of them.
--
-- The run's Chicago day, as the report filters on it: date(coalesce(start_time, skip_time) AT TIME ZONE
-- 'America/Chicago'), spelled exactly as QueryService.runReportRows writes it so the planner matches the two.
--
-- CONCURRENTLY, outside a transaction (runInTransaction: false): job_queue takes inserts every dispatch cycle.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_job_id_run ON public.job_queue (job_id, job_queue_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_run_day
    ON public.job_queue ((date(COALESCE(start_time, skip_time) AT TIME ZONE 'America/Chicago')));
