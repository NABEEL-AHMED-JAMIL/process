-- V220's way back. A workflow run still in flight beside another of its job would break the old index: it is closed first.
UPDATE public.job_queue q SET job_status = 'Interrupt', job_status_message = 'Closed by V220 rollback: another run of the job is in flight.'
 WHERE q.workflow_instance_id IS NOT NULL AND UPPER(q.job_status) IN ('QUEUE', 'START', 'RUNNING')
   AND EXISTS (SELECT 1 FROM public.job_queue o WHERE o.job_id = q.job_id AND o.run_lane = q.run_lane AND o.job_queue_id <> q.job_queue_id
               AND UPPER(o.job_status) IN ('QUEUE', 'START', 'RUNNING')
               AND (o.workflow_instance_id IS NULL OR o.job_queue_id < q.job_queue_id));
DROP INDEX IF EXISTS public.ux_job_queue_one_in_flight_per_workflow;
DROP INDEX IF EXISTS public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id, run_lane)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING');
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_workflow_lane;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS workflow_step_key;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS workflow_instance_id;
