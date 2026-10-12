-- MIG-334's way back: the API's download records go (the console's stay), then the columns. Runs and their files are
-- untouched; only the ids the API named them by are forgotten.
DELETE FROM public.file_access_log WHERE kind = 'api_file';
ALTER TABLE public.file_access_log DROP CONSTRAINT IF EXISTS ck_file_access_log_kind;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_kind CHECK (kind = 'run_dataset');
ALTER TABLE public.file_access_log DROP COLUMN IF EXISTS file_id;
ALTER TABLE public.file_access_log DROP COLUMN IF EXISTS client_id;
DROP INDEX IF EXISTS public.ux_run_output_file_id;
ALTER TABLE public.run_output DROP CONSTRAINT IF EXISTS ck_run_output_sha256;
ALTER TABLE public.run_output DROP CONSTRAINT IF EXISTS ck_run_output_file_id;
ALTER TABLE public.run_output DROP COLUMN IF EXISTS sha256;
ALTER TABLE public.run_output DROP COLUMN IF EXISTS file_id;
