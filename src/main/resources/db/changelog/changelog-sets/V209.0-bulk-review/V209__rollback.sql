-- V209's way back: review_policy (its policy goes with it) and the two bulk_id columns. Nothing else was touched.
DROP TABLE IF EXISTS public.review_policy;
ALTER TABLE public.run_approval DROP COLUMN IF EXISTS bulk_id;
ALTER TABLE public.run_review_decision DROP COLUMN IF EXISTS bulk_id;
