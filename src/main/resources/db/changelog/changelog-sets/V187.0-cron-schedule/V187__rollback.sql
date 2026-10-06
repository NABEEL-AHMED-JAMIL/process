-- V187's way back: the cron_expression column. A schedule saved as Cron in the meantime keeps frequency 'Cron' with
-- nothing to step by, so it runs its next slot and expires -- set those rows to another frequency before rolling back.
ALTER TABLE public.scheduler DROP COLUMN IF EXISTS cron_expression;
