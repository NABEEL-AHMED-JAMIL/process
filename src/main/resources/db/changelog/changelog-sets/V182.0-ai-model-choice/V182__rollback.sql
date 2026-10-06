-- V182's way back: result_record's four columns, run_ai_step with its sequence, and the two model_profiles columns.
-- Nothing else was touched.
ALTER TABLE public.result_record DROP CONSTRAINT IF EXISTS ck_result_record_model_choice;
ALTER TABLE public.result_record DROP COLUMN IF EXISTS model_choice;
ALTER TABLE public.result_record DROP COLUMN IF EXISTS model_option_id;
ALTER TABLE public.result_record DROP COLUMN IF EXISTS model;
ALTER TABLE public.result_record DROP COLUMN IF EXISTS step_key;
DROP TABLE IF EXISTS public.run_ai_step;
DROP SEQUENCE IF EXISTS public.run_ai_step_seq;
ALTER TABLE public.job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_model_profiles_object;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS model_profiles;
ALTER TABLE public.source_job DROP CONSTRAINT IF EXISTS ck_source_job_model_profiles_object;
ALTER TABLE public.source_job DROP COLUMN IF EXISTS model_profiles;
