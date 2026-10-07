-- MIG-360: an inbox arrival is never dropped. A file that lands while its job has a run in flight used to be recorded
-- Skipped ("upload it again once that run has finished"); now it is recorded Waiting, and the next run of the job takes
-- it -- the oldest waiting files first, as many at once as the trigger's batch_size (1, the default: one run per file).
--
-- - inbox_arrival.outcome: Waiting as well as Started and Skipped. A waiting arrival names no run (the existing check
--   still holds: Started <=> a run); started_at is when a waiting file's run was made (null for one started on arrival,
--   whose run was made at date_created).
-- - idx_inbox_arrival_waiting: the waiting files of each job, oldest first -- what the next run takes, and the queue
--   sweep's scan; partial, so it holds only the files that wait (none, most of the time).
-- - idx_inbox_arrival_run: the files a run was started for (a batch's keys, read once by the step engine).
-- - job_inbox_trigger.batch_size: how many waiting files one run takes, 1..50.
--
-- Expand-only: a widened check, a nullable column, a column with a default, a partial index. Both tables are already
-- under row security (V185); nothing new to grant.

ALTER TABLE public.inbox_arrival DROP CONSTRAINT ck_inbox_arrival_outcome;
ALTER TABLE public.inbox_arrival ADD CONSTRAINT ck_inbox_arrival_outcome CHECK (outcome IN ('Started', 'Skipped', 'Waiting'));
ALTER TABLE public.inbox_arrival ADD COLUMN started_at timestamp with time zone;
COMMENT ON COLUMN public.inbox_arrival.started_at IS 'MIG-360: when the run of a file that waited for its job was made; null for a file whose run started on arrival.';
CREATE INDEX idx_inbox_arrival_waiting ON public.inbox_arrival (job_id, inbox_arrival_id) WHERE outcome = 'Waiting';
CREATE INDEX idx_inbox_arrival_run ON public.inbox_arrival (job_queue_id) WHERE job_queue_id IS NOT NULL;

ALTER TABLE public.job_inbox_trigger ADD COLUMN batch_size integer DEFAULT 1 NOT NULL;
ALTER TABLE public.job_inbox_trigger ADD CONSTRAINT ck_job_inbox_trigger_batch_size CHECK (batch_size BETWEEN 1 AND 50);
COMMENT ON COLUMN public.job_inbox_trigger.batch_size IS 'MIG-360: how many files that waited for the job one run takes (oldest first); 1 = a run per file.';
