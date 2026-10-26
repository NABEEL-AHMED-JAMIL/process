-- V235's way back. A called run still in flight beside another of its job would break the narrower index: it is closed first.
UPDATE public.job_queue q SET job_status = 'Interrupt', job_status_message = 'Closed by V235 rollback: another run of the job is in flight.'
 WHERE q.called_by_run_id IS NOT NULL AND UPPER(q.job_status) IN ('QUEUE', 'START', 'RUNNING')
   AND EXISTS (SELECT 1 FROM public.job_queue o WHERE o.job_id = q.job_id AND o.run_lane = q.run_lane AND o.job_queue_id <> q.job_queue_id
               AND UPPER(o.job_status) IN ('QUEUE', 'START', 'RUNNING') AND o.workflow_instance_id IS NULL
               AND (o.called_by_run_id IS NULL OR o.job_queue_id < q.job_queue_id));
DROP INDEX IF EXISTS public.ix_job_queue_called_by;
DROP INDEX IF EXISTS public.ux_job_queue_one_in_flight_per_caller;
DROP INDEX IF EXISTS public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id, run_lane)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND workflow_instance_id IS NULL;
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_call_depth;
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_called_lane;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS call_depth;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS called_by_run_id;
