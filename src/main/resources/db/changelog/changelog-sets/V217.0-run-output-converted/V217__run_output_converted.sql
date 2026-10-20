-- convert_file (2026-10-20): a run's outputs may include a converted file -- a document, spreadsheet, presentation or
-- picture in the converter's formats, written to a bucket or kept with the run. run_output.format was csv, json, jsonl
-- or pdf (V188, V191); it now allows the converter's targets too.
--
-- Expand-only: the check constraint widens; every existing row still passes it.
ALTER TABLE public.run_output DROP CONSTRAINT ck_run_output_format;
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_format CHECK (format IN ('csv', 'json', 'jsonl', 'pdf',
    'docx', 'odt', 'rtf', 'txt', 'md', 'html', 'xlsx', 'ods', 'pptx', 'odp', 'png', 'jpg'));
