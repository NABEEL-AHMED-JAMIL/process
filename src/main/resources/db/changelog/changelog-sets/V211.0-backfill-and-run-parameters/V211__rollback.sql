-- V211's way back: the two backfill tables (their policies go with them) and their sequence, job_queue's four columns and
-- two indexes, and the one-in-flight index on job_id alone again (V83). A job with runs in flight in more than one lane
-- must have them closed first: the narrower index cannot be built over them.
DROP TABLE IF EXISTS public.backfill_slot;
DROP TABLE IF EXISTS public.backfill;
DROP SEQUENCE IF EXISTS public.backfill_seq;
DROP INDEX IF EXISTS public.idx_job_queue_backfill;
DROP INDEX IF EXISTS public.idx_job_queue_slot_completed;
DROP INDEX IF EXISTS public.ux_job_queue_one_in_flight_per_job;
CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON public.job_queue (job_id)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING');
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_lane_backfill;
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_run_lane;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS backfill_id;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS run_lane;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS run_slot_at;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS run_params;
