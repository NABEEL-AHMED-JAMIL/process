-- V185's way back (MIG-239): the two inbox tables (their policies and triggers go with them), the sequence, and
-- job_queue's two input columns. Nothing else was touched.
DROP TABLE IF EXISTS public.inbox_arrival;
DROP SEQUENCE IF EXISTS public.inbox_arrival_seq;
DROP TABLE IF EXISTS public.job_inbox_trigger;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS input_key;
ALTER TABLE public.job_queue DROP COLUMN IF EXISTS input_bucket;
