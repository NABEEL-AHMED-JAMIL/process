-- V183's way back: step_log and pipeline_definition with their sequences (their policies go with them), and
-- step_execution's five columns with their constraints and index. Nothing else was touched.
DROP TABLE IF EXISTS public.step_log;
DROP SEQUENCE IF EXISTS public.step_log_seq;
DROP INDEX IF EXISTS public.idx_step_execution_definition;
ALTER TABLE public.step_execution DROP CONSTRAINT IF EXISTS fk_step_execution_definition;
ALTER TABLE public.step_execution DROP CONSTRAINT IF EXISTS ux_step_execution_run_step_key;
ALTER TABLE public.step_execution DROP CONSTRAINT IF EXISTS ck_step_execution_on_error;
ALTER TABLE public.step_execution DROP CONSTRAINT IF EXISTS ck_step_execution_tries;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS status_message;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS on_error;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS tries;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS pipeline_definition_id;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS step_key;
DROP TABLE IF EXISTS public.pipeline_definition;
DROP SEQUENCE IF EXISTS public.pipeline_definition_seq;
