package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-34 against a real Postgres: V67 clears the retired 'billing' key out of the profiles and the
 * exceptions that still name it -- ignored by effectivePages already, but counted as an exception on
 * the users screen and ready to come back to life if the key ever did. Every other key stays.
 * Opt-in (IdentityPostgres).
 */
class BillingPageKeyRetiredPostgresTest {

    @Test
    void v67RemovesTheRetiredKeyAndNothingElse() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig34_billing")
                .migrateBefore("db/changelog/yaml/V67.0-retire-billing-page-key.yaml")) {
            JdbcTemplate sql = db.sql();
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (5001, 'Active', 'a', 'a')");
            sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) "
                + "VALUES (9001, 5001, 'x', 'x', 'Active', 'TENANT_USER', 'u@a.example')");
            sql.update("INSERT INTO page_access_profile (page_access_profile_id, tenant_id, profile_name, is_default, status) "
                + "VALUES (7001, 5001, 'Finance', false, 'Active')");
            for (String key : new String[] {"billing", "jobs", "reports"}) {
                sql.update("INSERT INTO page_access_profile_page (page_access_profile_id, page_key) VALUES (7001, ?)", key);
            }
            sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (9001, 5001, 'billing', true)");
            sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (9001, 5001, 'queue', false)");

            db.migrate();

            assertThat(sql.queryForList("SELECT page_key FROM page_access_profile_page WHERE page_access_profile_id = 7001 "
                + "ORDER BY page_key", String.class)).containsExactly("jobs", "reports");
            assertThat(sql.queryForList("SELECT page_key FROM user_page_access WHERE app_user_id = 9001", String.class))
                .containsExactly("queue");
        }
    }
}
