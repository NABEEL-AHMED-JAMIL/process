-- Owner 2026-10-21: a run Core's restart stopped is resumed once by Core itself. run_attempt.requested_via gains
-- 'platform', with no person or API client behind it (the attempt says "queued by the platform").
--
-- Expand-only: two CHECK constraints widened.
ALTER TABLE public.run_attempt DROP CONSTRAINT ck_run_attempt_via;
ALTER TABLE public.run_attempt DROP CONSTRAINT ck_run_attempt_who;
ALTER TABLE public.run_attempt ADD CONSTRAINT ck_run_attempt_via CHECK (requested_via IN ('console', 'api', 'platform'));
ALTER TABLE public.run_attempt ADD CONSTRAINT ck_run_attempt_who CHECK ((requested_via = 'console' AND requested_by_user_id IS NOT NULL)
    OR (requested_via = 'api' AND requested_by_client_id IS NOT NULL)
    OR (requested_via = 'platform' AND requested_by_user_id IS NULL AND requested_by_client_id IS NULL));
COMMENT ON COLUMN public.run_attempt.requested_via IS 'console (a person), api (an API client) or platform (Core resuming, once, a run its restart stopped).';
