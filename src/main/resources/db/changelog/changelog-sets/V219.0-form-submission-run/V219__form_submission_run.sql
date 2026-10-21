-- V219 (ZT-P15): a run's trigger reads "a form was submitted" from form_submission.job_queue_id for every run row of the Runs
-- explorer and the run history (RunFacets.triggerSql), as it reads api_intake, document_approval, inbox_arrival and backfill_slot
-- by theirs; this is that column's index. Expand-only.
CREATE INDEX IF NOT EXISTS idx_form_submission_run ON public.form_submission (job_queue_id) WHERE job_queue_id IS NOT NULL;
