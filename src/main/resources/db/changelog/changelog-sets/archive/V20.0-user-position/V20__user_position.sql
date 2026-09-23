-- A user's job title, shown beside their name and role.
--
-- Role says what the application lets someone do; position says what they do at the company.
-- They are not the same thing -- a Lead and a Software Engineer can both be TENANT_USER -- so
-- the org chart cannot be read off user_role.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS position VARCHAR(120);

COMMENT ON COLUMN app_user.position IS
    'Job title, e.g. Software Engineer or IT Administrator. Distinct from user_role, which is the permission level.';
