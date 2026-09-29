-- V189's way back: run_review_decision and run_review with their sequence (their policies and triggers go with them).
-- Nothing else was touched: the runs and their results are where they were -- only the run reviews are gone.
-- (result_record.review_status keeps what a run review last set it to.)
DROP TABLE IF EXISTS public.run_review_decision;
DROP TABLE IF EXISTS public.run_review;
DROP SEQUENCE IF EXISTS public.run_review_decision_seq;
