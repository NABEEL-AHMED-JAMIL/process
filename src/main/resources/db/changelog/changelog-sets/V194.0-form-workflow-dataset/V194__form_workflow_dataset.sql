-- MIG-279: a form's workflow and dataset.
--
--   form_definition.workflow_key        the workflow (workflow-service) a submission starts, if any.
--   form_definition.analytics_dataset_id the Analytics dataset its submissions are registered as (analytics-service's
--                                        id; a plain number -- the dataset may be removed there, the form stays).
--   form_definition.dataset_bucket       the workspace bucket (inbox alias) its rows are written to, one JSON file per
--                                        submission under datasets/forms/form-N/ -- remembered, so a status change
--                                        rewrites the row where it is with nobody signed in.
--   form_submission.workflow_instance_id the request the submission started, and workflow_status what it is now:
--                                        Pending, Overdue, Approved, Rejected, Completed, Cancelled, Failed -- kept from
--                                        workflow-service's platform.workflow.instance-changed.v1 -- or NotStarted
--                                        (workflow_reason says why).
--
-- Expand-only: nullable columns, no rewrite; row security is the tables' own (V192).

ALTER TABLE public.form_definition ADD COLUMN workflow_key character varying(64);
ALTER TABLE public.form_definition ADD COLUMN analytics_dataset_id bigint;
ALTER TABLE public.form_definition ADD COLUMN dataset_bucket character varying(255);

ALTER TABLE public.form_submission ADD COLUMN workflow_instance_id bigint;
ALTER TABLE public.form_submission ADD COLUMN workflow_status character varying(16);
ALTER TABLE public.form_submission ADD COLUMN workflow_reason character varying(2000);
ALTER TABLE public.form_submission ADD COLUMN workflow_updated_at timestamp with time zone;
ALTER TABLE public.form_submission ADD CONSTRAINT ck_form_submission_workflow_status
    CHECK (workflow_status IS NULL OR workflow_status IN ('Pending', 'Overdue', 'Approved', 'Rejected', 'Completed', 'Cancelled',
        'Failed', 'NotStarted'));

CREATE INDEX idx_form_submission_workflow ON public.form_submission (workflow_instance_id) WHERE workflow_instance_id IS NOT NULL;

COMMENT ON COLUMN public.form_definition.workflow_key IS 'MIG-279: the workflow a submission starts (workflow-service key), if any.';
COMMENT ON COLUMN public.form_submission.workflow_status IS 'MIG-279: the submission''s request now -- from workflow-service''s instance-changed events -- or NotStarted.';
