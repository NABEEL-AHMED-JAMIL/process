package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-106/107 against a real Postgres. V69.0: TenantSeedService's backfills, run once as a changeset now
 * that the service is deleted. V69.1: the cutover in etl_job -- skipped until identity-service's copy
 * script has frozen the six tables, then every foreign key from Core to them goes and so do the triggers
 * that kept source_job.assigned_username from app_user. Opt-in (IdentityPostgres).
 */
class IdentityCutoverPostgresTest {

    /** What identity-service/scripts/copy-from-etl-job.sh does to etl_job first, verbatim in effect. */
    static void freeze(JdbcTemplate sql) {
        sql.execute("CREATE OR REPLACE FUNCTION identity_moved_read_only() RETURNS trigger AS $$ BEGIN "
            + "RAISE EXCEPTION '% is read-only', TG_TABLE_NAME; END; $$ LANGUAGE plpgsql");
        for (String table : new String[] {"tenant", "app_user", "tenant_request", "page_access_profile", "page_access_profile_page",
            "user_page_access"}) {
            sql.execute("CREATE TRIGGER identity_moved_read_only BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON " + table
                + " FOR EACH STATEMENT EXECUTE PROCEDURE identity_moved_read_only()");
        }
    }

    private static int foreignKeysIntoIdentity(JdbcTemplate sql) {
        return sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' "
            + "AND confrelid::regclass::text IN ('tenant', 'app_user') "
            + "AND conrelid::regclass::text NOT IN ('tenant', 'app_user', 'tenant_request', 'page_access_profile', "
            + "'page_access_profile_page', 'user_page_access')", Integer.class);
    }

    private static int triggers(JdbcTemplate sql, String name) {
        return sql.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname = ?", Integer.class, name);
    }

    private static boolean ran(JdbcTemplate sql, String id) {
        return sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Integer.class, id) == 1;
    }

    @Test
    void theCutoverWaitsForTheFreezeThenCutsCoreLooseFromTheSixTables() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig107_cutover").migrate()) {
            JdbcTemplate sql = db.sql();
            assertThat(ran(sql, "69.1-identity-cutover")).as("not frozen yet: skipped, to be tried again").isFalse();
            assertThat(foreignKeysIntoIdentity(sql)).isPositive();
            assertThat(triggers(sql, "source_job_assigned_username")).isEqualTo(1);

            freeze(sql);
            db.migrate();

            assertThat(ran(sql, "69.1-identity-cutover")).isTrue();
            assertThat(foreignKeysIntoIdentity(sql)).as("every Core foreign key into Identity's tables").isZero();
            assertThat(triggers(sql, "source_job_assigned_username")).isZero();
            assertThat(triggers(sql, "app_user_renamed")).isZero();
            // Intra-Identity keys stay: C14/C16 and user_page_access's cascade from app_user are Identity's own.
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'fk_user_page_access_user'", Integer.class))
                .isEqualTo(1);
            // A job can now name a workspace and a person that exist only in identity_db, and keeps the name it is given.
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, "
                + "assigned_user_id, assigned_username) VALUES (77, now(), 'Auto', 'j', 'Active', 1, 424242, 919191, 'new@identity.example')");
            assertThat(sql.queryForObject("SELECT assigned_username FROM source_job WHERE job_id = 77", String.class))
                .isEqualTo("new@identity.example");
            assertThatThrownBy(() -> sql.update("UPDATE app_user SET full_name = full_name")).hasMessageContaining("read-only");
        }
    }

    @Test
    void theSeedBackfillsRunOnceAsAChangeset() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig106_backfill")
                .migrateBefore("db/changelog/yaml/V69.0-tenant-seed-backfills.yaml")) {
            JdbcTemplate sql = db.sql();
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (1000, 'Active', 'default', 'Default')");
            sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username) "
                + "VALUES (1, 'Platform administrator', 'x', 'Active', 'PLATFORM_ADMIN', 'admin@platform.local')");
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority) "
                + "VALUES (5, now(), 'Auto', 'orphan', 'Active', 1)");

            db.migrate();

            assertThat(ran(sql, "69.0-tenant-seed-backfills")).isTrue();
            assertThat(sql.queryForObject("SELECT tenant_id FROM source_job WHERE job_id = 5", Long.class)).isEqualTo(1000L);
            assertThat(sql.queryForObject("SELECT assigned_user_id FROM source_job WHERE job_id = 5", Long.class)).isEqualTo(1L);
            assertThat(sql.queryForObject("SELECT tenant_id FROM app_user WHERE app_user_id = 1", Long.class))
                .as("a person's tenant is never backfilled").isNull();
        }
    }
}
