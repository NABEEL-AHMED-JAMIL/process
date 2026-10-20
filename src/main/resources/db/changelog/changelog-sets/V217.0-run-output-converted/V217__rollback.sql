-- V217's way back: run_output.format is csv, json, jsonl or pdf again. A converted output recorded since would fail the
-- narrower check, so those rows go first (their kept files expire with the run's datasets as before).
DELETE FROM public.run_output WHERE format NOT IN ('csv', 'json', 'jsonl', 'pdf');
ALTER TABLE public.run_output DROP CONSTRAINT ck_run_output_format;
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_format CHECK (format IN ('csv', 'json', 'jsonl', 'pdf'));
