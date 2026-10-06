-- V102's way back: the five columns, their keys, indexes and triggers, and the parents' (id, tenant_id) keys.
DROP TRIGGER IF EXISTS job_queue_tenant_id_from_parent ON public.job_queue;
DROP TRIGGER IF EXISTS scheduler_tenant_id_from_parent ON public.scheduler;
DROP TRIGGER IF EXISTS job_audit_logs_tenant_id_from_parent ON public.job_audit_logs;
DROP TRIGGER IF EXISTS source_task_payload_tenant_id_from_parent ON public.source_task_payload;
DROP TRIGGER IF EXISTS pipeline_field_tenant_id_from_parent ON public.pipeline_field;
DROP FUNCTION IF EXISTS public.tenant_id_from_parent();
ALTER TABLE public.job_audit_logs DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.scheduler DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.source_task_payload DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.pipeline_field DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.source_job DROP CONSTRAINT IF EXISTS ux_source_job_id_tenant;
ALTER TABLE public.source_task DROP CONSTRAINT IF EXISTS ux_source_task_id_tenant;
ALTER TABLE public.pipeline DROP CONSTRAINT IF EXISTS ux_pipeline_key_tenant;
