-- MIG-255: a run's outputs may include a PDF -- the render_pdf step's report (media-service renders it; the file is kept
-- with the run like a Save File's). run_output.format was csv, json or jsonl (V188); it now allows pdf too.
--
-- Expand-only: the check constraint widens; every existing row still passes it.
ALTER TABLE public.run_output DROP CONSTRAINT ck_run_output_format;
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_format CHECK (format IN ('csv', 'json', 'jsonl', 'pdf'));
