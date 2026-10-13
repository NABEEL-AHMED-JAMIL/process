-- V212's way back: the retention tables (their policies go with them), the two job_queue columns, the two indexes and
-- the RETENTION alert kind (its rules first). What a pass removed stays removed.
DELETE FROM public.alert_rule WHERE kind = 'RETENTION';
ALTER TABLE public.alert_rule DROP CONSTRAINT ck_alert_rule_kind;
ALTER TABLE public.alert_rule ADD CONSTRAINT ck_alert_rule_kind CHECK (kind IN ('RUN_FAILED', 'SCHEDULE_MISSED', 'RUN_SLOW', 'REVIEW_WAITING',
    'SERVICE_UNHEALTHY', 'SERVICE_RESTARTED', 'MEMORY_HIGH', 'KAFKA_LAG', 'DISK_LOW', 'BILLING_MISMATCH'));
DROP INDEX IF EXISTS public.idx_run_dataset_step;
DROP INDEX IF EXISTS public.idx_form_submission_retention;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS logs_removed_at;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS outputs_removed_at;
DROP TABLE IF EXISTS public.retention_audit;
DROP SEQUENCE IF EXISTS public.retention_audit_seq;
DROP TABLE IF EXISTS public.retention_pass_class;
DROP TABLE IF EXISTS public.retention_pass;
DROP SEQUENCE IF EXISTS public.retention_pass_seq;
DROP TABLE IF EXISTS public.legal_hold;
DROP SEQUENCE IF EXISTS public.legal_hold_seq;
DROP TABLE IF EXISTS public.retention_rule;
DROP TABLE IF EXISTS public.retention_policy;
DROP TABLE IF EXISTS public.retention_platform;
