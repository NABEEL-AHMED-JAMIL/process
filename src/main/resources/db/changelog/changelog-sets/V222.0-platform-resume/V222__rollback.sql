-- V222's way back: the old constraints, NOT VALID so the platform's attempts already recorded stay; new ones are refused.
ALTER TABLE public.run_attempt DROP CONSTRAINT ck_run_attempt_via;
ALTER TABLE public.run_attempt DROP CONSTRAINT ck_run_attempt_who;
ALTER TABLE public.run_attempt ADD CONSTRAINT ck_run_attempt_via CHECK (requested_via IN ('console', 'api')) NOT VALID;
ALTER TABLE public.run_attempt ADD CONSTRAINT ck_run_attempt_who CHECK ((requested_via = 'console' AND requested_by_user_id IS NOT NULL)
    OR (requested_via = 'api' AND requested_by_client_id IS NOT NULL)) NOT VALID;
COMMENT ON COLUMN public.run_attempt.requested_via IS NULL;
