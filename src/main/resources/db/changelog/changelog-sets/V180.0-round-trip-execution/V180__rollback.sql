-- V180's way back: its four tables (their triggers and indexes go with them) and sequences, children first.
-- Nothing else was touched: job_queue keeps its shape, and tenant_id_from_parent stays V102's.
DROP TABLE IF EXISTS public.result_review;
DROP TABLE IF EXISTS public.result_record;
DROP TABLE IF EXISTS public.run_dataset;
DROP TABLE IF EXISTS public.step_execution;
DROP SEQUENCE IF EXISTS public.result_review_seq;
DROP SEQUENCE IF EXISTS public.result_record_seq;
DROP SEQUENCE IF EXISTS public.run_dataset_seq;
DROP SEQUENCE IF EXISTS public.step_execution_seq;
