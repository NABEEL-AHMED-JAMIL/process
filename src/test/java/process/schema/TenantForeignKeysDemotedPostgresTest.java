package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-166: every foreign key from a Core table onto Identity's six tables is demoted in EVERY etl_job built from
 * this changelog -- not only in one whose Identity tables were frozen and copied (V69.1). tenant_id, created_by,
 * updated_by and assigned_user_id stay as plain bigints; the compensating controls (Identity's lifecycle events,
 * WorkspaceRetirement, the orphan audit, the live-workspace check on writes) carry the guarantee instead. Keys
 * inside Identity's cluster and inside Core stay.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class TenantForeignKeysDemotedPostgresTest {

    private static final String SIX = "('tenant', 'app_user', 'tenant_request', 'page_access_profile', 'page_access_profile_page', "
        + "'user_page_access')";

    private static List<String> intoIdentity(JdbcTemplate sql) {
        return sql.queryForList("SELECT conrelid::regclass || '.' || conname || ' -> ' || confrelid::regclass FROM pg_constraint "
            + "WHERE contype = 'f' AND confrelid::regclass::text IN " + SIX + " AND conrelid::regclass::text NOT IN " + SIX
            + " ORDER BY 1", String.class);
    }

    private static long count(JdbcTemplate sql, String where) {
        return sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND " + where, Long.class);
    }

    @Test
    void aFreshBuildKeepsNoForeignKeyOntoIdentityAndEveryOtherOne() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("mig166_demote", "160.0-user-directory")) {
            JdbcTemplate sql = db.sql();
            List<String> before = intoIdentity(sql);
            long insideIdentity = count(sql, "conrelid::regclass::text IN " + SIX);
            long insideCore = count(sql, "confrelid::regclass::text NOT IN " + SIX);
            System.out.println("MIG-166 inventory, fresh build before V161: " + before.size() + " foreign keys onto Identity's tables "
                + before + "; " + insideIdentity + " inside Identity, " + insideCore + " inside Core");
            assertThat(before).as("the cutover (V69.1) has not run: the build still carries them").isNotEmpty();

            db.finish();

            assertThat(intoIdentity(sql)).isEmpty();
            assertThat(count(sql, "conrelid::regclass::text IN " + SIX)).as("Identity's own keys stay").isEqualTo(insideIdentity);
            assertThat(count(sql, "confrelid::regclass::text NOT IN " + SIX)).as("Core's own keys stay").isEqualTo(insideCore);
            // A row can name a workspace and a person that exist only in identity_db.
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, "
                + "created_by, assigned_user_id) VALUES (61, now(), 'Auto', 'j', 'Active', 1, 424242, 919191, 919191)");
        }
    }
}
