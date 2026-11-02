-- A rule for the three classes would break the old CHECK: those go first (their days fall back to the platform's).
DELETE FROM public.retention_rule WHERE data_class IN ('TRANSCRIPTS', 'RECORDINGS', 'CONVERSIONS');
ALTER TABLE public.retention_rule DROP CONSTRAINT ck_retention_rule_class;
ALTER TABLE public.retention_rule ADD CONSTRAINT ck_retention_rule_class CHECK (data_class IN ('RUN_OUTPUTS', 'RUN_LOGS', 'FILES',
    'DOCUMENTS', 'FORM_SUBMISSIONS', 'AI_CONVERSATIONS', 'NOTIFICATIONS', 'REPORTS', 'AUDIT'));
