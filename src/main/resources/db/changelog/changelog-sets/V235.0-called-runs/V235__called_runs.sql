-- V235 (MIG-424, owner 2026-10-26): a pipeline's "Run pipeline" step calls another pipeline of the same workspace and takes
-- its answer as rows. The called run is a normal run (billed, logged, its steps on the run's timeline) that says which run
-- called it (called_by_run_id) and how deep the call chain is (call_depth: 1 for a run a top-level run called, at most 3).
-- Like a workflow's run (V220) it goes beside the job's own runs: the job's one-in-flight index does not count it, and its
-- own index keeps one run in flight per job and calling run (a calling step calls one at a time). Always lane 0.
-- Expand-only, but for the one index rebuilt with a narrower predicate.

ALTER TABLE public.job_queue ADD COLUMN called_by_run_id bigint;
ALTER TABLE public.job_queue ADD COLUMN call_depth integer;
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_called_lane CHECK (called_by_run_id IS NULL OR run_lane = 0);
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_call_depth CHECK (call_depth IS NULL OR call_depth BETWEEN 1 AND 3);
COMMENT ON COLUMN public.job_queue.called_by_run_id IS 'V235: the run whose Run pipeline step started this run; null for every other trigger.';
COMMENT ON COLUMN public.job_queue.call_depth IS 'V235: how deep in a call chain this run is (1 to 3); null for a run nothing called.';

DROP INDEX public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id, run_lane)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND workflow_instance_id IS NULL AND called_by_run_id IS NULL;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_caller ON public.job_queue (job_id, called_by_run_id)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND called_by_run_id IS NOT NULL;
CREATE INDEX ix_job_queue_called_by ON public.job_queue (called_by_run_id) WHERE called_by_run_id IS NOT NULL;
