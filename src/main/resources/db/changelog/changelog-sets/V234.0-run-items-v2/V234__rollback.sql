-- V234's way back: kept reports are forgotten (a retry then makes them again), the items lose their values, flags and
-- pipeline versions; the runs, their items and outputs are untouched.
DROP TABLE IF EXISTS public.run_item_report;
DROP INDEX IF EXISTS public.idx_run_item_fields_ds;
DROP INDEX IF EXISTS public.idx_run_item_key;
ALTER TABLE public.run_item DROP CONSTRAINT IF EXISTS ck_run_item_flag;
ALTER TABLE public.run_item DROP COLUMN IF EXISTS pipeline_version;
ALTER TABLE public.run_item DROP COLUMN IF EXISTS flag;
ALTER TABLE public.run_item DROP COLUMN IF EXISTS fields_ds;
ALTER TABLE public.run_item DROP COLUMN IF EXISTS fields;
