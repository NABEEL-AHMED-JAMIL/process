-- V220 (2026-10-21, owner): several runs of one job at once when each comes from a different workflow request.
--
-- A workflow per patient runs the same pipelines for many patients at once, and an in-pipeline approval keeps a run in
-- flight until a reviewer decides: under one run in flight per job, the other patients' requests could not start their
-- run. A run a workflow started now says so (workflow_instance_id, workflow_step_key) and leaves the job's own lane to
-- Run now, schedules and the other triggers: the one-in-flight index no longer counts it. Its own index keeps one run in
-- flight per job and request, so a request asking twice (a retried call) does not start two. A workflow run is always in
-- lane 0 (backfills keep their lanes). Expand-only, but for the one index rebuilt with a narrower predicate.

ALTER TABLE public.job_queue ADD COLUMN workflow_instance_id bigint;
ALTER TABLE public.job_queue ADD COLUMN workflow_step_key varchar(128);
ALTER TABLE public.job_queue ADD CONSTRAINT ck_job_queue_workflow_lane CHECK (workflow_instance_id IS NULL OR run_lane = 0);
COMMENT ON COLUMN public.job_queue.workflow_instance_id IS 'V220: the workflow request (workflow-service instance) that started this run; null for every other trigger.';
COMMENT ON COLUMN public.job_queue.workflow_step_key IS 'V220: the run_pipeline step of that request.';

DROP INDEX public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id, run_lane)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND workflow_instance_id IS NULL;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_workflow ON public.job_queue (job_id, workflow_instance_id)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND workflow_instance_id IS NOT NULL;
