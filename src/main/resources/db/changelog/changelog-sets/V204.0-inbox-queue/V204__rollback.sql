-- MIG-360's way back: waiting files become Skipped (the old code knows no Waiting), then the columns, index and check.
UPDATE public.inbox_arrival SET outcome = 'Skipped',
    reason = 'A run of this job was in flight, so this file did not start another. Upload it again, or run the job, once that run has finished.'
    WHERE outcome = 'Waiting';
DROP INDEX IF EXISTS public.idx_inbox_arrival_run;
DROP INDEX IF EXISTS public.idx_inbox_arrival_waiting;
ALTER TABLE public.inbox_arrival DROP COLUMN IF EXISTS started_at;
ALTER TABLE public.inbox_arrival DROP CONSTRAINT ck_inbox_arrival_outcome;
ALTER TABLE public.inbox_arrival ADD CONSTRAINT ck_inbox_arrival_outcome CHECK (outcome IN ('Started', 'Skipped'));
ALTER TABLE public.job_inbox_trigger DROP CONSTRAINT IF EXISTS ck_job_inbox_trigger_batch_size;
ALTER TABLE public.job_inbox_trigger DROP COLUMN IF EXISTS batch_size;
