-- MIG-47 / MIG-86: invoice_line gets its own tenant_id, so it is filtered like every other tenant
-- table instead of only through whichever code remembered to check its parent invoice first.
-- The composite foreign key makes the invariant the database's: a line's tenant is its invoice's
-- tenant, and a row that says otherwise cannot be written.
-- No semicolons in these comments: the changeset splits statements on them.
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

UPDATE invoice_line l SET tenant_id = i.tenant_id FROM invoice i WHERE i.invoice_id = l.invoice_id AND l.tenant_id IS NULL;

ALTER TABLE invoice_line ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE invoice ADD CONSTRAINT ux_invoice_id_tenant UNIQUE (invoice_id, tenant_id);

ALTER TABLE invoice_line ADD CONSTRAINT fk_invoice_line_invoice_tenant
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoice (invoice_id, tenant_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS invoice_line_invoice_tenant ON invoice_line (invoice_id, tenant_id, sort);
