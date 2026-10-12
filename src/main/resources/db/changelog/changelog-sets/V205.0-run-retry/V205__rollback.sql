-- V205's way back: its two tables with their sequences (their policies and triggers go with them) and step_execution's
-- reused_from with its key and index. Nothing else was touched.
DROP INDEX IF EXISTS public.idx_step_execution_reused_from;
ALTER TABLE public.step_execution DROP CONSTRAINT IF EXISTS fk_step_execution_reused_from;
ALTER TABLE public.step_execution DROP COLUMN IF EXISTS reused_from;
DROP TABLE IF EXISTS public.run_input_file;
DROP SEQUENCE IF EXISTS public.run_input_file_seq;
DROP TABLE IF EXISTS public.run_attempt;
DROP SEQUENCE IF EXISTS public.run_attempt_seq;
