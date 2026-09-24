package process.time;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.model.pojo.Scheduler;
import process.model.repository.SchedulerRepository;
import process.util.BusinessTime;
import process.util.ProcessTimeUtil;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-96: the rehearsal on a full-size copy of a long-lived etl_job, not a seeded fixture.
 *
 * Not part of `mvn test` (the name matches none of surefire's patterns, so the suite has nothing to skip):
 * scripts/rehearse-timestamptz.sh copies a long-lived etl_job with pg_dump, runs this against the copy by name
 * (TIMESTAMPTZ_REHEARSAL_DB), and drops the copy. It refuses a database named etl_job: this migrates and rolls back
 * what it is given.
 *
 * Before: row counts, every converted column's stored text and the instant it means (read as Chicago), and what
 * ProcessTimeUtil makes of every scheduler row -- its next slot from a fixed clock. Then the changelog (V100, V101),
 * and the same again after: counts, instants and ProcessTimeUtil's answers equal. Then the rollback, and every stored
 * string equal to before, byte for byte. The copy is left migrated back: the script drops it.
 */
public class TimestamptzRehearsal {

    private static final Instant CLOCK = Instant.parse("2026-09-24T17:00:00Z");

    @Test
    void aFullCopyMigratesKeepsEveryInstantAndRollsBackToTheSameBytes() throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        String copy = System.getenv("TIMESTAMPTZ_REHEARSAL_DB");
        assumeTrue(server != null && copy != null, "NOTIFICATIONS_TEST_DB_URL and TIMESTAMPTZ_REHEARSAL_DB are not set");
        assertThat(copy).as("a copy, never the live database").isNotEqualTo("etl_job").matches("[a-z0-9_]+");

        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + copy + "$1"));
            pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
            pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
            pool.setMaximumPoolSize(2);
            JdbcTemplate sql = new JdbcTemplate(pool);

            Map<String, Long> rowsBefore = new TreeMap<>();
            Map<String, String> storedBefore = new TreeMap<>();
            Map<String, String> instantsBefore = new TreeMap<>();
            snapshot(sql, rowsBefore, storedBefore, instantsBefore);
            assertThat(TimestampColumns.dataType(sql, "job_queue", "start_time")).isEqualTo("timestamp without time zone");
            Map<Long, LocalDateTime> nextBefore = nextSlotsBefore(sql);
            System.out.println("REHEARSAL " + copy + ": " + rowsBefore + ", " + nextBefore.size() + " schedulers");

            List<String> before = sql.queryForList("SELECT id FROM databasechangelog", String.class);
            liquibase(pool, null);
            List<String> ran = sql.queryForList("SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
            ran.removeAll(before);
            System.out.println("REHEARSAL applied " + ran);
            assertThat(ran).contains(TimestamptzMigrationPostgresTest.V100, "101.0-naive-input-reads-as-chicago");

            Map<String, Long> rowsAfter = new TreeMap<>();
            Map<String, String> instantsAfter = new TreeMap<>();
            snapshot(sql, rowsAfter, new TreeMap<>(), instantsAfter);
            assertThat(rowsAfter).isEqualTo(rowsBefore);
            assertThat(instantsAfter).isEqualTo(instantsBefore);
            assertThat(nextSlotsAfter(pool)).isEqualTo(nextBefore);
            Long kept = sql.queryForObject("SELECT count(*) FROM timestamptz_v100_unrepresentable", Long.class);
            System.out.println("REHEARSAL gap values kept for the rollback: " + kept);

            liquibase(pool, ran.size());
            Map<String, String> storedAfterRollback = new TreeMap<>();
            snapshot(sql, new TreeMap<>(), storedAfterRollback, new TreeMap<>());
            assertThat(storedAfterRollback).isEqualTo(storedBefore);
            assertThat(TimestampColumns.dataType(sql, "job_queue", "start_time")).isEqualTo("timestamp without time zone");
        } finally {
            BusinessTime.useSystemClock();
        }
    }

    private static void snapshot(JdbcTemplate sql, Map<String, Long> rows, Map<String, String> stored, Map<String, String> instants) {
        for (Map.Entry<String, List<String>> table : TimestampColumns.CONVERTED.entrySet()) {
            if (sql.queryForObject("SELECT to_regclass('public.' || ?) IS NULL", Boolean.class, table.getKey())) {
                continue;
            }
            rows.put(table.getKey(), TimestampColumns.rows(sql, table.getKey()));
            for (String column : table.getValue()) {
                String name = table.getKey() + "." + column;
                stored.put(name, TimestampColumns.storedChecksum(sql, table.getKey(), column));
                instants.put(name, TimestampColumns.instantChecksum(sql, table.getKey(), column));
            }
        }
    }

    /** ProcessTimeUtil's next slot for every scheduler, from the naive columns as the old application read them. */
    private static Map<Long, LocalDateTime> nextSlotsBefore(JdbcTemplate sql) {
        BusinessTime.useClock(Clock.fixed(CLOCK, ZoneOffset.UTC));
        Map<Long, LocalDateTime> next = new LinkedHashMap<>();
        sql.query("SELECT scheduler_id, job_id, start_date, start_time, frequency, interval_value, days_of_week, day_of_month, "
            + "end_date, next_run_at::text AS next_run_at, expired FROM scheduler ORDER BY scheduler_id", row -> {
            Scheduler scheduler = new Scheduler();
            scheduler.setSchedulerId(row.getLong("scheduler_id"));
            scheduler.setJobId(row.getLong("job_id"));
            scheduler.setStartDate(row.getObject("start_date", LocalDate.class));
            scheduler.setStartTime(row.getObject("start_time", LocalTime.class));
            scheduler.setFrequency(row.getString("frequency"));
            scheduler.setIntervalValue(row.getString("interval_value"));
            scheduler.setDaysOfWeek(row.getString("days_of_week"));
            scheduler.setDayOfMonth(row.getObject("day_of_month") == null ? null : row.getInt("day_of_month"));
            scheduler.setEndDate(row.getObject("end_date", LocalDate.class));
            String stored = row.getString("next_run_at");
            scheduler.setNextRunAt(stored == null ? null : LocalDateTime.parse(stored.replace(' ', 'T')));
            next.put(scheduler.getSchedulerId(), ProcessTimeUtil.computeNextRun(scheduler));
        });
        return next;
    }

    /** The same, from the timestamptz columns through JPA as the application reads them now. */
    private static Map<Long, LocalDateTime> nextSlotsAfter(HikariDataSource pool) {
        BusinessTime.useClock(Clock.fixed(CLOCK, ZoneOffset.UTC));
        Map<Long, LocalDateTime> next = new LinkedHashMap<>();
        try (ScratchJpa jpa = new ScratchJpa(pool)) {
            SchedulerRepository schedulers = jpa.repository(SchedulerRepository.class);
            jpa.transactions().execute(status -> {
                for (Scheduler scheduler : schedulers.findAll()) {
                    next.put(scheduler.getSchedulerId(), ProcessTimeUtil.computeNextRun(scheduler));
                }
                return null;
            });
        }
        return new TreeMap<>(next);
    }

    /** Update when rollbackCount is null; otherwise roll that many back. */
    public static void liquibase(HikariDataSource pool, Integer rollbackCount) throws Exception {
        try (Connection connection = pool.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(TimestamptzRehearsal.class.getClassLoader()), database);
            // The changelog parameters the source database was built with (V70.2 takes two): a changeset's checksum
            // includes them, so without the same values Liquibase refuses the copy as edited.
            String parameters = System.getenv("TIMESTAMPTZ_REHEARSAL_PARAMS");
            if (parameters != null && !parameters.isEmpty()) {
                for (String pair : parameters.split(",")) {
                    String[] kv = pair.split("=", 2);
                    liquibase.setChangeLogParameter(kv[0], kv[1]);
                }
            }
            if (rollbackCount == null) {
                liquibase.update(new Contexts("init"), new LabelExpression());
            } else {
                liquibase.rollback(rollbackCount, new Contexts("init"), new LabelExpression());
            }
        }
    }
}
