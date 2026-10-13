-- ADR-027 (big-data PDFs): a step may write more than one file. render_pdf keeps its report and, when the report shows only
-- the first rows of a table, the full data as a CSV beside it; both are in the run's manifest. run_output was one row per
-- step execution (V188's ux_run_output_step); it is now one row per step execution and file name -- a later try's record
-- of the same file still replaces an earlier one's.
--
-- run_output.page_count: a PDF's pages, as media-service drew them (the console's Generated reports and run files show
-- it); null for every other file and for a row recorded before this.
--
-- Expand-only for the data: every existing row passes both new constraints, and the column is nullable. The relaxed
-- unique needs this release's Core (its insert names the new conflict target).
ALTER TABLE public.run_output ADD COLUMN page_count integer;
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_pages CHECK (page_count IS NULL OR page_count >= 0);
ALTER TABLE public.run_output ADD CONSTRAINT ux_run_output_step_name UNIQUE (step_execution_id, name);
ALTER TABLE public.run_output DROP CONSTRAINT ux_run_output_step;

COMMENT ON COLUMN public.run_output.page_count IS 'A PDF''s pages (render_pdf, ADR-027); null for any other file.';
