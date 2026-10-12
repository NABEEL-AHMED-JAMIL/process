-- MIG-334 (ADR-025): the customer API's read side in Core. Expand-only: columns, a wider check, no new table.
--
-- run_output.file_id: the file id a customer knows a run's made file by -- a ULID, like storage-service's stored_file
--   ids (the only id of a file a customer ever sees). GET /v1/files/{fileId} reads the file through it; the step engine
--   gives every new output one, and a row recorded before this has one given the first time the API lists it.
-- run_output.sha256: the made file's sha256 (hex), as the File schema says; null for a row recorded before this.
-- file_access_log: an API client's download of a file through its signed link is recorded beside the console's dataset
--   downloads -- kind api_file, the file id and the client.
--
-- Both tables are already tenant tables under row security (V188, V190 and V181); a new column changes nothing there.

ALTER TABLE public.run_output ADD COLUMN file_id character varying(26);
ALTER TABLE public.run_output ADD COLUMN sha256 character(64);
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_file_id CHECK (file_id IS NULL OR file_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$');
ALTER TABLE public.run_output ADD CONSTRAINT ck_run_output_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX ux_run_output_file_id ON public.run_output (file_id) WHERE file_id IS NOT NULL;
COMMENT ON COLUMN public.run_output.file_id IS 'MIG-334: the file id (ULID) the customer API names this made file by; GET /v1/files/{fileId}.';
COMMENT ON COLUMN public.run_output.sha256 IS 'MIG-334: the made file''s sha256 in hex; null for a row recorded before MIG-334.';

ALTER TABLE public.file_access_log ADD COLUMN client_id character varying(64);
ALTER TABLE public.file_access_log ADD COLUMN file_id character varying(26);
ALTER TABLE public.file_access_log DROP CONSTRAINT ck_file_access_log_kind;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_kind CHECK (kind IN ('run_dataset', 'api_file'));
COMMENT ON COLUMN public.file_access_log.client_id IS 'MIG-334: the API client whose signed link served (or refused) the file; null for a person.';
COMMENT ON COLUMN public.file_access_log.file_id IS 'MIG-334: kind api_file -- the file id the customer API names the file by.';
