-- MIG-13: user_page_access gets its own tenant_id, so the Hibernate tenant filter can scope it like
-- every other tenant table instead of only through whichever code remembered to look at its person.
-- The composite foreign key makes the invariant the database's: an exception's tenant is its person's
-- tenant. ON UPDATE CASCADE carries the exceptions along when a person is moved to another tenant.
-- A person whose tenant is cleared (made a platform admin) cannot keep exceptions: tenant_id is NOT NULL,
-- so the service drops them first, as it already drops the profile pointer.
-- No semicolons in these comments: the changeset splits statements on them.
ALTER TABLE user_page_access ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

UPDATE user_page_access a SET tenant_id = u.tenant_id FROM app_user u WHERE u.app_user_id = a.app_user_id AND a.tenant_id IS NULL;

ALTER TABLE user_page_access ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE app_user ADD CONSTRAINT ux_app_user_id_tenant UNIQUE (app_user_id, tenant_id);

ALTER TABLE user_page_access ADD CONSTRAINT fk_user_page_access_user_tenant
    FOREIGN KEY (app_user_id, tenant_id) REFERENCES app_user (app_user_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_user_page_access_tenant ON user_page_access (tenant_id);

COMMENT ON COLUMN user_page_access.tenant_id IS 'The person''s tenant, kept equal to app_user.tenant_id by fk_user_page_access_user_tenant (ON UPDATE CASCADE). Scoped by the tenant filter (MIG-13).';
