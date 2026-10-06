package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V187 (Wave 4) as the changelog builds it: scheduler.cron_expression, a nullable varchar(120) on a table V181 already
 * guards (its policy unchanged); V187 rolls back to exactly what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class CronScheduleSchemaPostgresTest {

    static final String V187 = "187.0-cron-schedule";

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("cron_schedule_v187");
        sql = db.sql();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static long columns() {
        return sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = 'scheduler' AND column_name = 'cron_expression'", Long.class);
    }

    @Test
    void theColumnIsANullableVarcharOf120AndTheTableStaysGuarded() {
        assertThat(sql.queryForMap("SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = 'scheduler' AND column_name = 'cron_expression'"))
            .containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 120)
            .containsEntry("is_nullable", "YES");
        assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'public.scheduler'::regclass"))
            .containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = 'scheduler' AND policyname = 'tenant_isolation'",
            Long.class)).isEqualTo(1);
    }

    @Test
    void theRollbackTakesItOffAndV187AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V187);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        try {
            assertThat(columns()).isZero();
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V187)).isEqualTo(1);
        assertThat(columns()).isEqualTo(1);
    }
}
