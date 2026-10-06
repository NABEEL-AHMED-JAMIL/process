-- Someone outside the platform asking for a workspace.
--
-- Kept as its own record rather than creating the tenant straight away: a request arrives from
-- an unauthenticated form, so it is a claim until a platform administrator agrees with it. The
-- tenant and its first administrator are created on approval, and the request keeps a pointer to
-- both so the decision can be traced afterwards.
CREATE SEQUENCE IF NOT EXISTS tenant_request_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS tenant_request (
    tenant_request_id BIGINT PRIMARY KEY,
    organisation_name VARCHAR(255) NOT NULL,
    contact_name      VARCHAR(255) NOT NULL,
    contact_email     VARCHAR(255) NOT NULL,
    -- What they say they want it for. Free text, shown to whoever reviews the request.
    purpose           TEXT,
    status            VARCHAR(24)  NOT NULL DEFAULT 'Pending',
    date_created      TIMESTAMP    NOT NULL DEFAULT now(),
    -- Filled in when the decision is made.
    decided_at        TIMESTAMP,
    decided_by        BIGINT,
    decision_note     TEXT,
    created_tenant_id BIGINT,
    created_user_id   BIGINT
);

-- One open request per address, so a refresh or a double click does not queue the same ask twice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_tenant_request_open_email
    ON tenant_request (lower(contact_email))
    WHERE status = 'Pending';

CREATE INDEX IF NOT EXISTS ix_tenant_request_status ON tenant_request (status);

-- The password mailed out on approval is a one-time credential, not a password. This marks the
-- account as owing a real one, so the emailed value stops working the moment it is used.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON TABLE tenant_request IS
    'A request from outside for a workspace. Becomes a tenant and its first administrator only when a platform administrator approves it.';
COMMENT ON COLUMN app_user.must_change_password IS
    'Set when an account is created with a generated password. Cleared once the person chooses their own.';
