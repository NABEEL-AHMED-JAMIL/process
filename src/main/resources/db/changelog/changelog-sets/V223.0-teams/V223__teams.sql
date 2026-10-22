-- F4 (MIG-401, owner T-Q1, 2026-10-21): groups became teams. An Approval step's reviewers may be a team (identity's teams,
-- the value its id), and an alert rule tells teams' members (recipient_teams). 'group' and recipient_groups stay only
-- until Core's TeamMigration has rewritten every one to the team its group became; V224 then takes them out.
--
-- Expand-only: one CHECK widened, one column added.
ALTER TABLE public.run_approval DROP CONSTRAINT ck_run_approval_reviewers;
ALTER TABLE public.run_approval ADD CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'team', 'group'));
COMMENT ON COLUMN public.run_approval.reviewers_kind IS 'role (reviewers_value TENANT_ADMIN or TENANT_USER) or team (reviewers_value the team id). F4: group became team.';

ALTER TABLE public.alert_rule ADD COLUMN recipient_teams character varying(512);
COMMENT ON COLUMN public.alert_rule.recipient_teams IS 'F4: comma list of team ids whose members are told as well.';
