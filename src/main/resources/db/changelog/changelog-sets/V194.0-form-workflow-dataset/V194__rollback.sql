-- V194's way back: the columns go; the requests in workflow-service and the datasets in analytics-service stay.
DROP INDEX IF EXISTS public.idx_form_submission_workflow;
ALTER TABLE public.form_submission DROP CONSTRAINT IF EXISTS ck_form_submission_workflow_status;
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS workflow_updated_at;
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS workflow_reason;
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS workflow_status;
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS workflow_instance_id;
ALTER TABLE public.form_definition DROP COLUMN IF EXISTS dataset_bucket;
ALTER TABLE public.form_definition DROP COLUMN IF EXISTS analytics_dataset_id;
ALTER TABLE public.form_definition DROP COLUMN IF EXISTS workflow_key;
