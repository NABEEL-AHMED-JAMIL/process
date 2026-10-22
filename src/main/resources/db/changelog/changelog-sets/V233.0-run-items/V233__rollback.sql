-- V233's way back: the item tables go (the runs, their steps and outputs are untouched); item retries and item events
-- are no longer recorded.
DELETE FROM public.api_event_out WHERE kind = 'item';
ALTER TABLE public.api_event_out DROP CONSTRAINT IF EXISTS ck_api_event_out_item;
ALTER TABLE public.api_event_out DROP CONSTRAINT IF EXISTS ck_api_event_out_kind;
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_kind
    CHECK (kind IN ('run_status', 'file_made', 'review_decided', 'submission', 'alert')) NOT VALID;
ALTER TABLE public.api_event_out DROP COLUMN IF EXISTS run_item_id;
ALTER TABLE public.run_attempt DROP CONSTRAINT IF EXISTS ck_run_attempt_item_mode;
ALTER TABLE public.run_attempt DROP COLUMN IF EXISTS item_mode;
ALTER TABLE public.run_attempt DROP COLUMN IF EXISTS item_keys;
DELETE FROM public.file_access_log WHERE kind IN ('run_item_file', 'run_item_zip') OR action = 'link';
ALTER TABLE public.file_access_log DROP CONSTRAINT IF EXISTS ck_file_access_log_kind;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_kind CHECK (kind IN ('run_dataset', 'api_file')) NOT VALID;
ALTER TABLE public.file_access_log DROP CONSTRAINT IF EXISTS ck_file_access_log_action;
ALTER TABLE public.file_access_log ADD CONSTRAINT ck_file_access_log_action CHECK (action IN ('download', 'preview')) NOT VALID;
DROP TABLE IF EXISTS public.run_item_file;
DROP TABLE IF EXISTS public.run_item;
