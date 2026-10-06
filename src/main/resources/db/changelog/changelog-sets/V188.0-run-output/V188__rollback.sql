-- V188's way back: run_output with its sequence (its policy and trigger go with it). Nothing else was touched; the
-- runs' datasets and uploaded objects are where they were -- only the manifest of them is gone.
DROP TABLE IF EXISTS public.run_output;
DROP SEQUENCE IF EXISTS public.run_output_seq;
