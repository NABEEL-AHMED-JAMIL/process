package process.engine;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import process.engine.cron.ProcessCron;
import process.model.repository.JobQueueRepository;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C4 (MIG-137) against a real Postgres: which rows the stall sweep selects, and the lock it runs under.
 *
 * The unit test pins what the sweep does with a row; this one runs the sweep's own native query --
 * read off JobQueueRepository.findStalledRuns, not copied -- against the V50 schema, so the
 * predicate is exercised by the database that evaluates it: UPPER() makes the status case-blind,
 * COALESCE(start_time, date_created) sweeps a run that was never dispatched, the comparison is
 * strict, and the order is by id. The application clock is controlled by choosing the cutoff: the
 * query is handed one, it never reads the database's clock.
 *
 * And the lock, against a real shedlock table, configured exactly as ProcessConfig configures it and
 * with the durations read off ProcessCron's own @SchedulerLock: a second instance cannot run the
 * sweep while the first holds it (lockAtMostFor 5 minutes), nor straight after it finishes
 * (lockAtLeastFor 5 seconds).
 *
 * Opt-in, like EtlJobChangelogPostgresTest: runs when NOTIFICATIONS_TEST_DB_URL and its user and
 * password point at a Postgres server; builds a throwaway database and drops it after.
 */
class StalledRunSweepPostgresTest {

    /** The application's "now" for every row below; the cutoff is six hours before it. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);
    private static final LocalDateTime CUTOFF = NOW.minusMinutes(360);

    private static String server;
    private static String scratch;
    private static HikariDataSource pool;
    private JdbcTemplate sql;

    @BeforeAll
    static void buildSchema() throws Exception {
        server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "stall_sweep_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + scratch);
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(pool);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("init");
        liquibase.setResourceLoader(new DefaultResourceLoader(StalledRunSweepPostgresTest.class.getClassLoader()));
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (pool == null) {
            return;
        }
        pool.close();
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + scratch);
        }
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void emptyTables() {
        this.sql = new JdbcTemplate(pool);
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9001 AND 9099");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9001 AND 9099");
        this.sql.update("DELETE FROM shedlock");
    }

    private void job(long jobId) {
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (?, ?, 'Auto', ?, 'Active', 1)", jobId, Timestamp.valueOf(NOW.minusDays(1)), "stall-" + jobId);
    }

    private void run(long jobQueueId, long jobId, String status, LocalDateTime startTime, LocalDateTime dateCreated) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (?, ?, ?, ?, ?, 'Active')", jobQueueId, jobId, status,
            startTime == null ? null : Timestamp.valueOf(startTime), Timestamp.valueOf(dateCreated));
    }

    /** The repository's own SQL, with JPA's positional ?1 as JDBC's ?. */
    private static String nativeQuery(String method, Class<?>... parameters) throws Exception {
        Query query = JobQueueRepository.class.getMethod(method, parameters).getAnnotation(Query.class);
        return query.value().replace("?1", "?");
    }

    @Test
    void theSweepSelectsEveryInFlightCaseSpellingByCoalesceStrictlyBeforeTheCutoffInIdOrder() throws Exception {
        LocalDateTime longAgo = NOW.minusHours(7);
        for (long jobId = 9001; jobId <= 9012; jobId++) {
            this.job(jobId);
        }
        // Inserted out of id order, so the ordering is the query's and not the heap's.
        this.run(111, 9011, "Running", CUTOFF.minusSeconds(1), longAgo);   // just before the cutoff
        this.run(101, 9001, "Start", longAgo, longAgo);                    // the classic strand
        this.run(102, 9002, "RUNNING", longAgo, longAgo);                  // upper case on disk
        this.run(103, 9003, "queue", null, longAgo);                       // never dispatched
        this.run(104, 9004, "Start", NOW.minusHours(5), longAgo);          // start_time wins
        this.run(105, 9005, "Completed", longAgo, longAgo);
        this.run(106, 9006, "Failed", longAgo, longAgo);
        this.run(107, 9007, "Interrupt", longAgo, longAgo);
        this.run(108, 9008, "Skip", longAgo, longAgo);
        this.run(109, 9009, "Missed", longAgo, longAgo);
        this.run(110, 9010, "Running", CUTOFF, longAgo);                   // exactly at the cutoff
        this.run(112, 9012, "Queue", null, NOW.minusHours(1));             // queued an hour ago

        List<Long> swept = this.sql.queryForList(
            nativeQuery("findStalledRuns", LocalDateTime.class).replace("job_queue.*", "job_queue.job_queue_id"),
            Long.class, Timestamp.valueOf(CUTOFF));

        assertThat(swept).containsExactly(101L, 102L, 103L, 111L);
    }

    /** The busy test the sweep's "leave the job row alone" rests on reads status the same case-blind way. */
    @Test
    void theInFlightCountTheSweepConsultsIsCaseBlindToo() throws Exception {
        this.job(9020);
        this.run(120, 9020, "start", NOW.minusHours(1), NOW.minusHours(1));
        this.run(121, 9020, "Interrupt", NOW.minusHours(8), NOW.minusHours(8));
        this.run(122, 9020, "Completed", NOW.minusHours(9), NOW.minusHours(9));

        Integer count = this.sql.queryForObject(nativeQuery("getCountForInQueueJobByJobId", Long.class),
            Integer.class, 9020L);

        assertThat(count).isEqualTo(1);
    }

    // ---- the lock --------------------------------------------------------------------------------------

    private static Duration shedLockDuration(String text) throws Exception {
        // ShedLock's own parser for the annotation's strings, so "5M" means here what it means to it.
        Class<?> type = Class.forName("net.javacrumbs.shedlock.spring.aop.StringToDurationConverter");
        Field instance = type.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        @SuppressWarnings("unchecked")
        Converter<String, Duration> converter = (Converter<String, Duration>) instance.get(null);
        return converter.convert(text);
    }

    private static Duration heldFor(Map<String, Object> row) {
        return Duration.between(((Timestamp) row.get("locked_at")).toLocalDateTime(),
            ((Timestamp) row.get("lock_until")).toLocalDateTime());
    }

    private static LockProvider instance() {
        // As ProcessConfig builds it: a JdbcTemplate over the application's data source, defaults otherwise.
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(pool)).build());
    }

    @Test
    void aSecondInstanceCannotSweepWhileTheFirstHoldsTheLockNorStraightAfter() throws Exception {
        SchedulerLock annotation = ProcessCron.class.getMethod("reconcileStalledRuns").getAnnotation(SchedulerLock.class);
        Duration atMost = shedLockDuration(annotation.lockAtMostFor());
        Duration atLeast = shedLockDuration(annotation.lockAtLeastFor());
        assertThat(atMost).isEqualTo(Duration.ofMinutes(5));
        assertThat(atLeast).isEqualTo(Duration.ofSeconds(5));
        LockConfiguration config = new LockConfiguration(annotation.name(), atMost, atLeast);

        Optional<SimpleLock> first = instance().lock(config);
        assertThat(first).as("the first instance takes the sweep").isPresent();
        Map<String, Object> held = this.sql.queryForMap(
            "SELECT lock_until, locked_at FROM shedlock WHERE name = ?", "reconcileStalledRuns");
        // Within a second: ShedLock stamps locked_at a few milliseconds after it computed lock_until.
        assertThat(heldFor(held)).isBetween(atMost.minusSeconds(1), atMost);

        assertThat(instance().lock(config)).as("a second instance while the sweep runs").isEmpty();

        first.get().unlock();
        Map<String, Object> released = this.sql.queryForMap(
            "SELECT lock_until, locked_at FROM shedlock WHERE name = ?", "reconcileStalledRuns");
        assertThat(heldFor(released)).isBetween(atLeast.minusSeconds(1), atLeast);
        assertThat(instance().lock(config)).as("a second instance straight after a fast sweep").isEmpty();
    }
}
