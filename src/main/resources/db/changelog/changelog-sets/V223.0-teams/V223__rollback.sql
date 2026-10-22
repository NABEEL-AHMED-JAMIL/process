-- Back to groups only once no approval names a team (a team id is not an access profile id).
ALTER TABLE public.alert_rule DROP COLUMN recipient_teams;
ALTER TABLE public.run_approval DROP CONSTRAINT ck_run_approval_reviewers;
ALTER TABLE public.run_approval ADD CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'group'));
