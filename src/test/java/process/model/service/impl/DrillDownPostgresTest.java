package process.model.service.impl;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.MessageQSearchDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.security.TenantContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-8 (DEF-126) against a database built from the changelog, the only kind that can show the defect:
 * no test against today's database could, because its job_queue has the column order the positional
 * read was written against.
 *
 * The drill-down's own SQL, as QueryService builds it, runs against two job_queue tables holding the
 * same run: the V50 baseline's, and one laid out the way ddl-auto lays a table out (id first, then
 * alphabetical -- attempt at index 1). Both must render the same field values. Before the columns were
 * named, the second threw from Timestamp.valueOf("1").
 *
 * Opt-in, like EtlJobChangelogPostgresTest: runs when NOTIFICATIONS_TEST_DB_URL and its user and
 * password point at a Postgres server; builds a throwaway database and drops it after.
 */
class DrillDownPostgresTest {

    private static String server;
    private static String scratch;
    private static HikariDataSource pool;

    @BeforeAll
    static void buildSchema() throws Exception {
        server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "drill_down_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + scratch);
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        pool.setMaximumPoolSize(1);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(pool);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("init");
        liquibase.setResourceLoader(new DefaultResourceLoader(DrillDownPostgresTest.class.getClassLoader()));
        liquibase.afterPropertiesSet();

        JdbcTemplate sql = new JdbcTemplate(pool);
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (1196, '2026-09-01 09:00', 'Auto', 'Nightly claims', 'Active', 1, 2901)");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, end_time, job_id, job_send, job_status, job_status_message, "
            + "run_manual, skip_manual, skip_time, start_time, status, attempt) VALUES (5073, '2026-09-21 14:03:07', '2026-09-21 14:05:09', "
            + "1196, true, 'Completed', 'done', false, false, NULL, '2026-09-21 14:04:01', 'Active', 1)");
        // The same run in a table laid out the way ddl-auto lays one out: the id, then alphabetical.
        sql.execute("CREATE SCHEMA hibernate_order");
        sql.execute("CREATE TABLE hibernate_order.job_queue (job_queue_id bigint PRIMARY KEY, attempt integer NOT NULL, bucket varchar(255), "
            + "callback_token_attempt integer, callback_token_expires_at timestamp, callback_token_hash varchar(64), "
            + "date_created timestamp NOT NULL, end_time timestamp, job_id bigint NOT NULL, job_send boolean, "
            + "job_status varchar(255) NOT NULL, job_status_message text, next_attempt_at timestamp, output_folder varchar(255), "
            + "run_manual boolean, skip_manual boolean, skip_time timestamp, start_time timestamp, status varchar(255) NOT NULL)");
        sql.update("INSERT INTO hibernate_order.job_queue SELECT job_queue_id, attempt, bucket, callback_token_attempt, "
            + "callback_token_expires_at, callback_token_hash, date_created, end_time, job_id, job_send, job_status, job_status_message, "
            + "next_attempt_at, output_folder, run_manual, skip_manual, skip_time, start_time, status FROM public.job_queue");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (pool != null) {
            pool.close();
        }
        if (scratch != null) {
            try (Connection admin = admin(); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + scratch);
            }
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theBaselinesJobQueueRendersEveryField() throws Exception {
        assertTheRun(this.drillDown("public"));
        assertTheRun(this.runLog("public"));
    }

    @Test
    void aJobQueueLaidOutByDdlAutoRendersTheSameFields() throws Exception {
        assertTheRun(this.drillDown("hibernate_order, public"));
        assertTheRun(this.runLog("hibernate_order, public"));
    }

    /** GET /dashboard.json/weeklyHrRunningStatisticsDimensionDetail, from QueryService's SQL to the DTO. */
    private SourceJobQueueDto drillDown(String searchPath) throws Exception {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        String built = new QueryService().weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", null);
        QueryService rows = mock(QueryService.class);
        when(rows.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", null)).thenReturn(built);
        when(rows.executeQuery(anyString())).thenAnswer(call -> this.rows(searchPath, call.getArgument(0)));
        ResponseDto response = new DashboardServiceImpl(rows, null, null, null, null)
            .weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", null);
        @SuppressWarnings("unchecked")
        List<SourceJobQueueDto> runs = (List<SourceJobQueueDto>) ((Map<String, Object>) response.getData()).get("sourceJobQueues");
        assertThat(runs).hasSize(1);
        return runs.get(0);
    }

    /** The run-log list reads the same eleven columns by position, through fetchJobQLog. */
    private SourceJobQueueDto runLog(String searchPath) throws Exception {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        MessageQSearchDto search = new MessageQSearchDto();
        search.setFromDate("2026-09-21");
        search.setToDate("2026-09-21");
        List<Object[]> result = this.rows(searchPath, new QueryService().fetchJobQLog(search, false));
        assertThat(result).hasSize(1);
        Object[] row = result.get(0);
        // The select list, by name, must be exactly the drill-down's: that is the order both readers use.
        assertThat(DrillDownColumnsTest.selectList(new QueryService().fetchJobQLog(search, false)))
            .hasSameSizeAs(DrillDownColumnsTest.RUN_COLUMNS);
        SourceJobQueueDto run = new SourceJobQueueDto();
        run.setJobQueueId(((Number) row[0]).longValue());
        run.setDateCreated(Timestamp.valueOf(String.valueOf(row[1])));
        run.setEndTime(((Timestamp) row[2]).toLocalDateTime());
        run.setJobId(((Number) row[3]).longValue());
        run.setJobStatus(JobStatus.valueOf(String.valueOf(row[5])));
        run.setJobStatusMessage(String.valueOf(row[6]));
        run.setRunManual((Boolean) row[7]);
        run.setSkipTime(row[9] == null ? null : ((Timestamp) row[9]).toLocalDateTime());
        run.setStartTime(((Timestamp) row[10]).toLocalDateTime());
        return run;
    }

    private List<Object[]> rows(String searchPath, String query) {
        JdbcTemplate sql = new JdbcTemplate(pool);
        sql.execute("SET search_path TO " + searchPath);
        try {
            List<Object[]> rows = new ArrayList<>();
            sql.query(query, resultSet -> {
                Object[] row = new Object[resultSet.getMetaData().getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = resultSet.getObject(i + 1);
                }
                rows.add(row);
            });
            return rows;
        } finally {
            sql.execute("SET search_path TO public");
        }
    }

    private static void assertTheRun(SourceJobQueueDto run) {
        assertThat(run.getJobQueueId()).isEqualTo(5073L);
        assertThat(run.getDateCreated()).isEqualTo(Timestamp.valueOf("2026-09-21 14:03:07"));
        assertThat(run.getEndTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 14, 5, 9));
        assertThat(run.getJobId()).isEqualTo(1196L);
        assertThat(run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(run.getJobStatusMessage()).isEqualTo("done");
        assertThat(run.getRunManual()).isFalse();
        assertThat(run.getSkipTime()).isNull();
        assertThat(run.getStartTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 14, 4, 1));
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }
}
