-- The tools redesign (owner-approved 2026-11-02): the retention classes TRANSCRIPTS, RECORDINGS and CONVERSIONS, held by
-- media-service -- Audio Transcript's transcripts, the recordings it keeps for playback (at most 30 days) and the Document
-- Converter's conversions not kept in a bucket (at most 30 days). A policy may name them like any other class. Expand-only.
ALTER TABLE public.retention_rule DROP CONSTRAINT ck_retention_rule_class;
ALTER TABLE public.retention_rule ADD CONSTRAINT ck_retention_rule_class CHECK (data_class IN ('RUN_OUTPUTS', 'RUN_LOGS', 'FILES',
    'DOCUMENTS', 'FORM_SUBMISSIONS', 'AI_CONVERSATIONS', 'NOTIFICATIONS', 'TRANSCRIPTS', 'RECORDINGS', 'CONVERSIONS', 'REPORTS',
    'AUDIT'));
