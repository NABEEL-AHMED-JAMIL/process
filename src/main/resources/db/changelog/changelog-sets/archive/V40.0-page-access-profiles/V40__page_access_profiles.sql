-- Access profiles: which console pages a tenant user may open.
--
-- Until now the three roles were the whole story. A tenant user could open every ordinary page
-- -- Reports, Analytics Studio, the Tools, AI Agents -- and the only way to take one away was to
-- take the person's account away. A tenant admin asked for something in between: "the operators
-- run pipelines, the analysts also get reports and analytics, compliance reads and touches
-- nothing".
--
-- The shape is a named bundle of pages per workspace, and one bundle per person. Not a matrix
-- of per-user checkboxes: an admin onboarding somebody says "Analyst" once, and changing what
-- an Analyst can see is one edit rather than one per person. Pages are a fixed catalogue in the
-- code (process.model.enums.PageKey) rather than a table, so a renamed route can never leave an
-- orphaned grant behind and a page nobody wrote yet cannot be granted.
--
-- Nothing changes for a workspace that never creates a profile: PageAccessService resolves "no
-- profile assigned and no default profile" to "every page", which is what every tenant user has
-- today. The admin roles are never subject to any of this.

CREATE SEQUENCE IF NOT EXISTS page_access_profile_Seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS page_access_profile (
    page_access_profile_id BIGINT       PRIMARY KEY,
    -- Always owned by one workspace. There is no platform-wide profile: the platform admin
    -- opens everything anyway, and a bundle shared across tenants would let one workspace's
    -- admin change what another workspace's people can see.
    tenant_id              BIGINT       NOT NULL,
    profile_name           VARCHAR(100) NOT NULL,
    description            VARCHAR(500),
    -- At most one per workspace (partial unique index below): the bundle a tenant user gets
    -- when nobody picked one for them. None means "every page", see the header.
    is_default             BOOLEAN      NOT NULL DEFAULT FALSE,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'Active',
    date_created           TIMESTAMP    NOT NULL DEFAULT now(),
    date_updated           TIMESTAMP,
    created_by             BIGINT,
    updated_by             BIGINT,
    CONSTRAINT fk_page_access_profile_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_page_access_profile_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_page_access_profile_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id),
    -- Two profiles called "Analyst" in one workspace is a mistake, in another workspace it is
    -- just a common word.
    CONSTRAINT uq_page_access_profile_name UNIQUE (tenant_id, profile_name)
);

CREATE INDEX IF NOT EXISTS idx_page_access_profile_tenant_id ON page_access_profile (tenant_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_page_access_profile_default
    ON page_access_profile (tenant_id) WHERE is_default;

-- The pages in a bundle. page_key is one of PageKey's values; the application refuses anything
-- else at save, and an unknown key that somehow lands here is simply ignored at resolution, so
-- a page removed from the catalogue later degrades to "not granted" rather than to an error.
CREATE TABLE IF NOT EXISTS page_access_profile_page (
    page_access_profile_id BIGINT      NOT NULL,
    page_key               VARCHAR(64) NOT NULL,
    CONSTRAINT pk_page_access_profile_page PRIMARY KEY (page_access_profile_id, page_key),
    CONSTRAINT fk_page_access_profile_page_profile FOREIGN KEY (page_access_profile_id)
        REFERENCES page_access_profile (page_access_profile_id) ON DELETE CASCADE
);

-- The person's bundle. Nullable: null means "the workspace default, or everything". The
-- application refuses to delete a profile that is still assigned, so no ON DELETE clause is
-- relied on -- but SET NULL is the right fallback should a row be removed by hand: the person
-- drops back to the default rather than to a broken reference.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS page_access_profile_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_page_access_profile'
    ) THEN
        ALTER TABLE app_user
            ADD CONSTRAINT fk_app_user_page_access_profile
            FOREIGN KEY (page_access_profile_id) REFERENCES page_access_profile (page_access_profile_id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_app_user_page_access_profile_id ON app_user (page_access_profile_id);

COMMENT ON TABLE page_access_profile IS
    'A named bundle of console pages a workspace grants to its tenant users. One per person via app_user.page_access_profile_id; is_default names the bundle for people with none.';
COMMENT ON TABLE page_access_profile_page IS
    'The page keys (PageKey enum) inside a page_access_profile.';
