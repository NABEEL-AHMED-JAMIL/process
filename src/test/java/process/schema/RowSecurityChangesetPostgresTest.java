package process.schema;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V181 (MIG-258) as Postgres holds it, and its way back: every policy says platform-commons' OWNED_ROWS (the five
 * platform-readable tables' SELECT policies with tenant_id IS NULL in front), the four derived-column trigger functions
 * run as their owner, the two functions of the Dashboard's day index are LEAKPROOF -- and a rollback undoes all of it,
 * leaving every table and row, and applying again puts it back.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class RowSecurityChangesetPostgresTest {

    static final List<String> DEFINER = Arrays.asList("scheduler_dispatch_eligible_from_job", "scheduler_dispatch_eligible_on_write",
        "source_job_assigned_username_on_write", "source_job_assigned_username_on_rename");

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("rls_changeset");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    private static String[] owned(JdbcTemplate sql) {
        sql.execute("CREATE TABLE rls_reference (tenant_id bigint)");
        try {
            sql.execute("CREATE POLICY reference ON rls_reference USING (" + RowSecurity.OWNED_ROWS + ")");
            sql.execute("CREATE POLICY shared ON rls_reference FOR SELECT USING (tenant_id IS NULL OR " + RowSecurity.OWNED_ROWS + ")");
            return new String[]{sql.queryForObject("SELECT qual FROM pg_policies WHERE policyname = 'reference'", String.class),
                sql.queryForObject("SELECT qual FROM pg_policies WHERE policyname = 'shared'", String.class)};
        } finally {
            sql.execute("DROP TABLE rls_reference");
        }
    }

    @Test
    void appliedRolledBackAndAppliedAgain() throws Exception {
        JdbcTemplate sql = db.sql();
        this.holds(sql);
        long rows = sql.queryForObject("SELECT count(*) FROM tenant", Long.class);

        // The changesets after V181 first (V182's run_ai_step brings its own policy), then V181 alone.
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = '181.0-row-level-security')", Integer.class);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname = 'public'", Long.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_class WHERE relrowsecurity OR relforcerowsecurity", Long.class)).isZero();
        assertThat(sql.queryForList("SELECT proname FROM pg_proc WHERE prosecdef AND proname = ANY (?)", String.class,
            (Object) DEFINER.toArray(new String[0]))).isEmpty();
        assertThat(sql.queryForObject("SELECT bool_or(proleakproof) FROM pg_proc WHERE oid IN ('pg_catalog.timezone(text, timestamptz)'::regprocedure,"
            + " 'pg_catalog.date(timestamp)'::regprocedure)", Boolean.class)).isFalse();
        assertThat(sql.queryForObject("SELECT has_table_privilege('process_app', 'public.source_job', 'SELECT')", Boolean.class)).isFalse();
        assertThat(sql.queryForObject("SELECT count(*) FROM tenant", Long.class)).isEqualTo(rows);

        db.finish();
        this.holds(sql);
    }

    private void holds(JdbcTemplate sql) {
        String[] reference = owned(sql);
        assertThat(sql.queryForList("SELECT DISTINCT qual FROM pg_policies WHERE policyname = 'tenant_isolation'", String.class))
            .containsExactly(reference[0]);
        assertThat(sql.queryForList("SELECT DISTINCT with_check FROM pg_policies WHERE policyname IN ('tenant_isolation', 'tenant_insert', "
            + "'tenant_update')", String.class)).containsExactly(reference[0]);
        assertThat(sql.queryForList("SELECT DISTINCT qual FROM pg_policies WHERE policyname IN ('tenant_update', 'tenant_delete')",
            String.class)).containsExactly(reference[0]);
        assertThat(sql.queryForList("SELECT DISTINCT qual FROM pg_policies WHERE policyname = 'tenant_read'", String.class))
            .containsExactly(reference[1]);
        assertThat(sql.queryForList("SELECT tablename FROM pg_policies WHERE policyname = 'tenant_read' ORDER BY 1", String.class))
            .containsExactly("kafka_connection_profile", "lookup_data", "source_task_type", "storage_connection", "user_directory");
        assertThat(sql.queryForList("SELECT proname FROM pg_proc WHERE prosecdef AND proname = ANY (?) ORDER BY 1", String.class,
            (Object) DEFINER.toArray(new String[0]))).containsExactlyInAnyOrderElementsOf(DEFINER);
        assertThat(sql.queryForObject("SELECT bool_and(proleakproof) FROM pg_proc WHERE oid IN ('pg_catalog.timezone(text, timestamptz)'::regprocedure,"
            + " 'pg_catalog.date(timestamp)'::regprocedure)", Boolean.class)).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND roles <> '{process_app}'", Long.class))
            .isZero();
    }
}
