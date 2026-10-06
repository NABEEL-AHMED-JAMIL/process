package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-258's performance acceptance for the dispatcher: its pick-up -- SchedulerRepository.claimNextDueScheduler, one
 * due slot, FOR UPDATE SKIP LOCKED -- plans the same under row security as without it, on the partial due index, over
 * 40 workspaces' 20,000 schedules. The dispatcher claims across every workspace (its @AcrossTenants grant), so the
 * policy's predicate is true for every row and only ever a filter; the plan is asserted, and the time compared, as
 * process_app with app.all_tenants on against the login that row security never applies to.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class DispatchClaimUnderRowSecurityPostgresTest {

    static final String CLAIM = "select scheduler.* from scheduler where scheduler.dispatch_eligible and scheduler.expired = false"
        + " and scheduler.next_run_at <= now() and scheduler.scheduler_id not in (-1) order by scheduler.next_run_at asc,"
        + " scheduler.scheduler_id asc limit 1 for update skip locked";

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("dispatch_rls");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) SELECT 3000 + g, 'Active', 'WS' || g, 'Workspace ' || g"
            + " FROM generate_series(1, 40) g");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "SELECT g, now() - interval '2 days', 'Auto', 'job ' || g, 'Active', 1, 3001 + g % 40 FROM generate_series(1, 20000) g");
        // Most schedules are due later; a few hundred are due now, as on a busy platform.
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, next_run_at, expired) "
            + "SELECT g, g, current_date - 1, '00:00:00', 'Daily', now() + ((g % 50) - 1) * interval '1 hour', false"
            + " FROM generate_series(1, 20000) g");
        sql.execute("ANALYZE scheduler");
        sql.execute("ANALYZE source_job");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void theClaimPlansOnTheDueIndexAsProcessAppAcrossTenants() throws Exception {
        String asLogin = plan(false);
        String asApp = plan(true);
        System.out.println("-- claim as the login\n" + asLogin + "\n-- claim as process_app, all tenants\n" + asApp);
        assertThat(asLogin).contains("idx_scheduler_due");
        assertThat(asApp).contains("idx_scheduler_due").doesNotContain("Seq Scan");
    }

    /** The same 200 claims (each rolled back) take about as long; a generous bound, since a laptop is noisy. */
    @Test
    void theClaimCostsAboutTheSame() throws Exception {
        long login = time(false);
        long app = time(true);
        System.out.println("-- 200 claims: login " + login / 1_000_000 + " ms, process_app " + app / 1_000_000 + " ms");
        assertThat(app).isLessThan(Math.max(login * 3, 200_000_000L));
    }

    private static String plan(boolean underRowSecurity) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            enter(s, underRowSecurity);
            List<String> lines = new ArrayList<>();
            try (ResultSet rows = s.executeQuery("EXPLAIN " + CLAIM)) {
                while (rows.next()) {
                    lines.add(rows.getString(1));
                }
            }
            return String.join("\n", lines);
        }
    }

    private static long time(boolean underRowSecurity) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            enter(s, underRowSecurity);
            c.setAutoCommit(false);
            for (int warm = 0; warm < 20; warm++) {
                s.executeQuery(CLAIM).close();
                c.rollback();
            }
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                try (ResultSet row = s.executeQuery(CLAIM)) {
                    assertThat(row.next()).isTrue();
                }
                c.rollback();
            }
            return System.nanoTime() - start;
        }
    }

    private static void enter(Statement s, boolean underRowSecurity) throws Exception {
        if (underRowSecurity) {
            s.execute("SET ROLE process_app");
            s.execute("SELECT set_config('app.tenant_id', '', false), set_config('app.all_tenants', 'on', false)");
        }
    }
}
