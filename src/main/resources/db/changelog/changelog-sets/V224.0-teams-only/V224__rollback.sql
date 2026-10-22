ALTER TABLE public.alert_rule ADD COLUMN recipient_groups character varying(512);
COMMENT ON COLUMN public.alert_rule.recipient_groups IS 'Comma list of access profile ids (groups) whose members are told as well.';
ALTER TABLE public.run_approval DROP CONSTRAINT ck_run_approval_reviewers;
ALTER TABLE public.run_approval ADD CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'team', 'group'));
