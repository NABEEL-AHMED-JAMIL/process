-- V210's way back: the four tables (their policies go with them), the two sequences, api_event_out's alert rows, columns
-- and check, and the four partial indexes. Nothing else was touched.
DROP INDEX IF EXISTS public.idx_run_approval_waiting;
DROP INDEX IF EXISTS public.idx_run_review_pending;
DROP INDEX IF EXISTS public.idx_job_queue_skipped_at;
DROP INDEX IF EXISTS public.idx_job_queue_failed_ended;
DELETE FROM public.api_event_out WHERE kind = 'alert';
ALTER TABLE public.api_event_out DROP CONSTRAINT IF EXISTS ck_api_event_out_alert;
ALTER TABLE public.api_event_out DROP CONSTRAINT IF EXISTS ck_api_event_out_kind;
ALTER TABLE public.api_event_out ADD CONSTRAINT ck_api_event_out_kind CHECK (kind IN ('run_status', 'file_made', 'review_decided', 'submission'));
ALTER TABLE public.api_event_out DROP COLUMN IF EXISTS alert_event;
ALTER TABLE public.api_event_out DROP COLUMN IF EXISTS alert_incident_id;
DROP TABLE IF EXISTS public.platform_probe;
DROP TABLE IF EXISTS public.service_health_sample;
DROP TABLE IF EXISTS public.alert_incident;
DROP SEQUENCE IF EXISTS public.alert_incident_seq;
DROP TABLE IF EXISTS public.alert_rule;
DROP SEQUENCE IF EXISTS public.alert_rule_seq;
