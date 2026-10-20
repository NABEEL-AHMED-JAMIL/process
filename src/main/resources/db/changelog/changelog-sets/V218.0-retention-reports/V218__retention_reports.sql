-- Report Studio phase 7 (ADR-028 section 17, plan 1.10): the retention class REPORTS, held by media-service -- old report
-- versions' files (never the current version, never an approved or signed one), the frozen data no kept version uses, and
-- failed generations' data after 7 days. A policy may name it like any other class. Expand-only.
ALTER TABLE public.retention_rule DROP CONSTRAINT ck_retention_rule_class;
ALTER TABLE public.retention_rule ADD CONSTRAINT ck_retention_rule_class CHECK (data_class IN ('RUN_OUTPUTS', 'RUN_LOGS', 'FILES',
    'DOCUMENTS', 'FORM_SUBMISSIONS', 'AI_CONVERSATIONS', 'NOTIFICATIONS', 'REPORTS', 'AUDIT'));
