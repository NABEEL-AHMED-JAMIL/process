-- MIG-364's way back: the three tables (their policies, triggers and indexes go with them) and the sequence.
DROP TABLE IF EXISTS public.run_document_read;
DROP TABLE IF EXISTS public.document_approval;
DROP SEQUENCE IF EXISTS public.document_approval_seq;
DROP TABLE IF EXISTS public.job_document_trigger;
