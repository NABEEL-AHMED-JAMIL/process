-- F4 (MIG-401): every approval names a role or a team and every alert rule tells teams, so 'group' leaves the CHECK and
-- recipient_groups is dropped. Applied on the first start after Core's TeamMigration rewrote the last of them (the
-- precondition counts them; until then it waits for the next start).
ALTER TABLE public.run_approval DROP CONSTRAINT ck_run_approval_reviewers;
ALTER TABLE public.run_approval ADD CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'team'));
ALTER TABLE public.alert_rule DROP COLUMN recipient_groups;
