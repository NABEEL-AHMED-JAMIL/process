-- Per-person exceptions to an access profile.
--
-- A profile is the baseline: what everyone on "Operator" opens. The grid on the Access profiles
-- screen shows one checkbox per person per page, and a checkbox has to mean what it says --
-- tick it and that page opens for that one person, whatever their profile says. So an exception
-- is one row here: this person, this page, open (true) or withheld (false). A page with no row
-- is the profile's answer. Ticking a box back to what the profile already says deletes the row
-- rather than storing an exception that changes nothing, so the table only ever holds real
-- differences and "reset to profile" is a delete.
--
-- Resolution, in PageAccessServiceImpl.resolve: profile pages, plus every page allowed here,
-- minus every page withheld here. Admins are never subject to any of it.

CREATE TABLE IF NOT EXISTS user_page_access (
    app_user_id  BIGINT      NOT NULL,
    page_key     VARCHAR(64) NOT NULL,
    allowed      BOOLEAN     NOT NULL,
    date_created TIMESTAMP   NOT NULL DEFAULT now(),
    created_by   BIGINT,
    CONSTRAINT pk_user_page_access PRIMARY KEY (app_user_id, page_key),
    CONSTRAINT fk_user_page_access_user FOREIGN KEY (app_user_id) REFERENCES app_user (app_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_page_access_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id)
);

COMMENT ON TABLE user_page_access IS
    'Per-person exceptions to the access profile: one row per page that differs from the profile, allowed=true opens it, false withholds it.';
