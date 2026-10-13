-- V208's way back: one row per step execution again. A step that recorded several files since keeps only its first
-- (the others' kept files still expire with the run's datasets), and the page counts go.
DELETE FROM public.run_output o USING public.run_output k
 WHERE o.step_execution_id = k.step_execution_id AND o.run_output_id > k.run_output_id;
ALTER TABLE public.run_output ADD CONSTRAINT ux_run_output_step UNIQUE (step_execution_id);
ALTER TABLE public.run_output DROP CONSTRAINT ux_run_output_step_name;
ALTER TABLE public.run_output DROP CONSTRAINT ck_run_output_pages;
ALTER TABLE public.run_output DROP COLUMN page_count;
