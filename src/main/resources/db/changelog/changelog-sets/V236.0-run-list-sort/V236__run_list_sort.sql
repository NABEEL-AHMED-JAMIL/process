-- V236, owner 2026-10-27 ("add a sort on each table"): a schedule's run history sorted by a column, a window at a time
-- (SourceJobServiceImpl, RunListSort.jobHistory: WHERE job_id = ? ORDER BY <column>, job_queue_id LIMIT/OFFSET). Without
-- these, a sort read every run of the job through idx_job_queue_job_id and sorted them all -- 525,000 a year for a minute job.
--
-- Each expression is spelled exactly as RunListSort writes it, so the planner matches the two (RunListSortPostgresTest).
-- date_created is never empty: one index serves Queued both ways. Started, Ended and Duration can be empty and sort
-- NULLS LAST both ways; their index is DESC NULLS LAST, the direction a first click picks (newest / longest first) --
-- oldest / shortest first is a top-N sort of the job's runs. Status (a CASE over job_status) has no index: it is the same
-- top-N sort, and the status filter is the way to see one status.
--
-- CONCURRENTLY, outside a transaction (runInTransaction: false), as V200.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_job_queued ON public.job_queue (job_id, date_created, job_queue_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_job_started
    ON public.job_queue (job_id, (COALESCE(start_time, skip_time)) DESC NULLS LAST, job_queue_id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_job_ended
    ON public.job_queue (job_id, end_time DESC NULLS LAST, job_queue_id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_queue_job_duration
    ON public.job_queue (job_id, (end_time - start_time) DESC NULLS LAST, job_queue_id DESC);
