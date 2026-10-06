-- V193's way back: form_upload and its sequence, and form_version. The files stay in the inbox bucket; the submissions keep their answers
-- (which name the files by key).
DROP TABLE IF EXISTS public.form_upload;
DROP SEQUENCE IF EXISTS public.form_upload_seq;
DROP TABLE IF EXISTS public.form_version;
