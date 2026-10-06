-- V192's way back: form_submission and form_definition with their sequences (their policies, trigger and indexes go
-- with them). Nothing else was touched: the runs a submission started, and the files it left in the inbox bucket,
-- stay where they are -- only the forms and their submissions are gone.
DROP TABLE IF EXISTS public.form_submission;
DROP TABLE IF EXISTS public.form_definition;
DROP SEQUENCE IF EXISTS public.form_submission_seq;
DROP SEQUENCE IF EXISTS public.form_definition_seq;
