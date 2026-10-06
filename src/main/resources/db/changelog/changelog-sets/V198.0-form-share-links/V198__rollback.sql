ALTER TABLE public.form_upload DROP COLUMN IF EXISTS share_ticket;
ALTER TABLE public.form_submission DROP CONSTRAINT IF EXISTS fk_form_submission_share_link;
ALTER TABLE public.form_submission DROP COLUMN IF EXISTS share_link_id;
DROP TABLE IF EXISTS public.form_share_link;
DROP SEQUENCE IF EXISTS public.form_share_link_seq;
DROP TABLE IF EXISTS public.form_share_policy;
