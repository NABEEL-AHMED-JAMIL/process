-- V214's way back: the engine settings' override and audit tables (their policies go with them), the rule mute, and the
-- four sample columns (empty: the samples are kept a day and refill as they are taken). The platform's engine values in
-- orchestration_setting stay; the old code ignores the keys it does not know.
DROP TABLE IF EXISTS public.engine_setting_audit;
DROP SEQUENCE IF EXISTS public.engine_setting_audit_seq;
DROP TABLE IF EXISTS public.engine_setting_override;
ALTER TABLE public.alert_rule DROP COLUMN IF EXISTS muted_until;
ALTER TABLE public.service_health_sample ADD COLUMN IF NOT EXISTS response_ms integer;
ALTER TABLE public.service_health_sample ADD COLUMN IF NOT EXISTS cpu_pct numeric(5,1);
ALTER TABLE public.service_health_sample ADD COLUMN IF NOT EXISTS threads integer;
ALTER TABLE public.service_health_sample ADD COLUMN IF NOT EXISTS mean_ms numeric(12,1);
