-- MIG-280: the step a submission's request waits at now ("Manager approval"), as workflow-service's instance-changed
-- event names it (the open task's name; NULL once the request ended). Submissions shows it as the Stage column.
-- Expand-only: one nullable column; row security is the table's own (V192).
ALTER TABLE public.form_submission ADD COLUMN workflow_stage character varying(200);
COMMENT ON COLUMN public.form_submission.workflow_stage IS 'MIG-280: the step the submission''s request waits at now (its open task''s name), from workflow-service''s events.';
