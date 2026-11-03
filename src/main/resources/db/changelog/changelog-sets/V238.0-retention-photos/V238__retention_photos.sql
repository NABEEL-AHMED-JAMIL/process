-- Image review (the third Tools page, owner-approved 2026-11-03): the retention class PHOTOS, held by media-service -- the
-- tool's photos, their measurements and draft descriptions (one not sent on is removed after 30 days). A policy may name it like any
-- other class. Expand-only.
ALTER TABLE public.retention_rule DROP CONSTRAINT ck_retention_rule_class;
ALTER TABLE public.retention_rule ADD CONSTRAINT ck_retention_rule_class CHECK (data_class IN ('RUN_OUTPUTS', 'RUN_LOGS', 'FILES',
    'DOCUMENTS', 'FORM_SUBMISSIONS', 'AI_CONVERSATIONS', 'NOTIFICATIONS', 'TRANSCRIPTS', 'RECORDINGS', 'CONVERSIONS', 'PHOTOS',
    'REPORTS', 'AUDIT'));
