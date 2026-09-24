package process.time;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-28 / MIG-96 / MIG-163: V100 turns every naive timestamp process owns into timestamptz, reading each
 * value as the America/Chicago wall-clock it was written as; V101 makes a session that names no zone read
 * naive input the same way. Built from the changelog up to V100, loaded with the committed before-fixture
 * (src/test/resources/timestamptz/before-fixture.csv), then migrated -- the order a long-lived etl_job
 * goes through it.
 *
 * What is asserted is the interpretation, not the storage: each row must mean the same instant after V100 as
 * the application made of it before (the fixture's instant column, and a per-column checksum over every row).
 * And the way back: V100's rollback restores every stored string byte for byte, the DST gap included.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class TimestamptzMigrationPostgresTest {

    static final String V100 = "100.0-timestamps-are-instants";

    private static ScratchEtlJob db;
    private static final List<String[]> FIXTURE = new ArrayList<>();
    private static final Map<String, String> STORED_BEFORE = new TreeMap<>();
    private static final Map<String, String> INSTANTS_BEFORE = new TreeMap<>();
    private static final Map<String, Long> ROWS_BEFORE = new TreeMap<>();

    @BeforeAll
    static void migrateAFixtureDatabase() throws Exception {
        db = ScratchEtlJob.buildUpTo("timestamptz_v100", V100);
        JdbcTemplate sql = db.sql();
        seedBaseRows(sql);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
            TimestamptzMigrationPostgresTest.class.getResourceAsStream("/timestamptz/before-fixture.csv"), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }
                String[] row = line.split(",");
                FIXTURE.add(row);
                String where = whereKey(sql, row[0], row[1]);
                int updated = sql.update(String.format("UPDATE public.%s SET %s = ?::timestamp WHERE %s", row[0], row[2], where), row[3]);
                assertThat(updated).as("fixture row %s %s", row[0], row[1]).isEqualTo(1);
            }
        }
        for (Map.Entry<String, List<String>> table : TimestampColumns.CONVERTED.entrySet()) {
            ROWS_BEFORE.put(table.getKey(), TimestampColumns.rows(sql, table.getKey()));
            for (String column : table.getValue()) {
                STORED_BEFORE.put(table.getKey() + "." + column, TimestampColumns.storedChecksum(sql, table.getKey(), column));
                INSTANTS_BEFORE.put(table.getKey() + "." + column, TimestampColumns.instantChecksum(sql, table.getKey(), column));
            }
        }
        db.finish();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    /** One row under every key the fixture names, with placeholder times the fixture then overwrites. */
    private static void seedBaseRows(JdbcTemplate sql) {
        // identity-prep's V68 table, as it stands on the live etl_job: V100 converts it wherever it exists.
        sql.execute("CREATE TABLE IF NOT EXISTS identity_signing_key (kid VARCHAR(64) PRIMARY KEY, algorithm VARCHAR(10) NOT NULL DEFAULT 'RS256', "
            + "public_key TEXT NOT NULL, private_key_sealed TEXT, status VARCHAR(10) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT now(), "
            + "retired_at TIMESTAMP)");
        sql.update("INSERT INTO identity_signing_key (kid, public_key, status) VALUES ('v100-kid', 'pk', 'retired')");
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (900, 'Active', 'V100', 'Fixture')");
        sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username, tenant_id) "
            + "VALUES (900201, 'Fixture', 'x', 'Active', 'TENANT_USER', 'fixture@v100.test', 900)");
        sql.update("INSERT INTO tenant_request (tenant_request_id, organisation_name, contact_name, contact_email) "
            + "VALUES (900501, 'o', 'c', 'c@v100.test')");
        sql.update("INSERT INTO page_access_profile (page_access_profile_id, tenant_id, profile_name) VALUES (900601, 900, 'fixture')");
        sql.update("INSERT INTO user_page_access (app_user_id, page_key, allowed, tenant_id) VALUES (900201, 'HOME', true, 900)");
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, date_created) VALUES (900701, 'V100_FIXTURE', 'x', now())");
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id) VALUES (900801, 'F-V100', 'fixture', 900)");
        sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, bootstrap_servers, is_default, profile_name, "
            + "security_protocol, status, tenant_id) VALUES (900901, 'b:9092', false, 'fixture', 'PLAINTEXT', 'Active', 900)");
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (900902, 'w', 'd', 'topic=v100&partitions=[*]', 900)");
        sql.update("INSERT INTO tenant_task_type_kafka_route (tenant_task_type_kafka_route_id, kafka_connection_profile_id, "
            + "source_task_type_id, tenant_id) VALUES (900951, 900901, 900902, 900)");
        for (long job : new long[] {900001, 900002}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Auto', 'fixture', 'Active', 1, 900)", job);
        }
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired) "
            + "VALUES (900301, 900001, '2026-01-01', '02:30', 'Daily', '1', false)");
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired) "
            + "VALUES (900302, 900002, '2026-01-01', '01:30', 'Daily', '1', false)");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES (900101, now(), 900001, 'Completed', 'Active')");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES (900102, now(), 900002, 'Completed', 'Active')");
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
            + "VALUES (900401, now(), 900101, 'fixture', 'Active')");
        sql.update("INSERT INTO worker_callback_receipt (job_queue_id, idempotency_key, request, received_at) "
            + "VALUES (900101, 'v100-key', 'fixture', now())");
        sql.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES ('v100-fixture', now(), now(), 'fixture')");
    }

    private static String whereKey(JdbcTemplate sql, String table, String key) {
        return TimestampColumns.keyExpression(sql, table) + " = '" + key.replace("'", "''") + "'";
    }

    /** What a JVM defaulted to America/Chicago made of a naive string: GregorianCalendar's rules, as pgjdbc parsed it. */
    private static Instant legacyReading(String stored) {
        LocalDateTime wall = LocalDateTime.parse(stored.replace(' ', 'T'));
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("America/Chicago"));
        calendar.clear();
        calendar.set(wall.getYear(), wall.getMonthValue() - 1, wall.getDayOfMonth(), wall.getHour(), wall.getMinute(), wall.getSecond());
        return calendar.toInstant().plusNanos(wall.getNano());
    }

    private static String utcText(JdbcTemplate sql, String table, String column, String key) {
        return sql.queryForObject(String.format("SELECT to_char(%s AT TIME ZONE 'UTC', %s) FROM public.%s WHERE %s",
            column, TimestampColumns.UTC_TEXT, table, whereKey(sql, table, key)), String.class);
    }

    @Test
    void theFixtureRecordsWhatTheOldApplicationMadeOfEachString() {
        // The fixture's instant column is not an opinion: it is what the Chicago JVM computed.
        for (String[] row : FIXTURE) {
            assertThat(legacyReading(row[3])).as("%s.%s %s", row[0], row[2], row[1]).isEqualTo(Instant.parse(row[4]));
        }
    }

    @Test
    void aChicagoMorningIsStoredAsTheSameInstantNotRelabelled() {
        JdbcTemplate sql = db.sql();
        // The acceptance row: 08:00 in Chicago in January is 14:00 UTC. A plain cast would have made it 08:00+00.
        assertThat(sql.queryForObject("SELECT (start_time AT TIME ZONE 'UTC')::text FROM job_queue WHERE job_queue_id = 900101", String.class))
            .isEqualTo("2026-01-15 14:00:00");
        assertThat(TimestampColumns.dataType(sql, "job_queue", "start_time")).isEqualTo("timestamp with time zone");
    }

    @Test
    void everyFixtureRowMeansTheInstantTheOldApplicationReadItAs() {
        JdbcTemplate sql = db.sql();
        Map<String, String> wrong = new LinkedHashMap<>();
        for (String[] row : FIXTURE) {
            String now = utcText(sql, row[0], row[2], row[1]);
            if (!Instant.parse(now).equals(Instant.parse(row[4]))) {
                wrong.put(row[0] + "." + row[2] + " " + row[1], row[3] + " -> " + now + ", expected " + row[4]);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void theDstEdgesResolveToTheDocumentedInstants() {
        JdbcTemplate sql = db.sql();
        // 2026-03-08 02:30 does not exist in Chicago: it becomes 08:30Z, i.e. 03:30 CDT.
        assertThat(utcText(sql, "scheduler", "next_run_at", "900301")).isEqualTo("2026-03-08T08:30:00.000000Z");
        assertThat(sql.queryForObject("SELECT (next_run_at AT TIME ZONE 'America/Chicago')::text FROM scheduler WHERE scheduler_id = 900301",
            String.class)).isEqualTo("2026-03-08 03:30:00");
        // 2026-11-01 01:30 happens twice: it becomes the second one, 07:30Z (01:30 CST), as the old JVM read it.
        assertThat(utcText(sql, "scheduler", "next_run_at", "900302")).isEqualTo("2026-11-01T07:30:00.000000Z");
        assertThat(sql.queryForObject("SELECT (next_run_at AT TIME ZONE 'America/Chicago')::text FROM scheduler WHERE scheduler_id = 900302",
            String.class)).isEqualTo("2026-11-01 01:30:00");
    }

    @Test
    void rowCountsAndPerColumnInstantsReconcile() {
        JdbcTemplate sql = db.sql();
        Map<String, String> instantsAfter = new TreeMap<>();
        Map<String, Long> rowsAfter = new TreeMap<>();
        for (Map.Entry<String, List<String>> table : TimestampColumns.CONVERTED.entrySet()) {
            rowsAfter.put(table.getKey(), TimestampColumns.rows(sql, table.getKey()));
            for (String column : table.getValue()) {
                assertThat(TimestampColumns.dataType(sql, table.getKey(), column)).as(table.getKey() + "." + column)
                    .isEqualTo("timestamp with time zone");
                instantsAfter.put(table.getKey() + "." + column, TimestampColumns.instantChecksum(sql, table.getKey(), column));
            }
        }
        assertThat(rowsAfter).isEqualTo(ROWS_BEFORE);
        assertThat(instantsAfter).isEqualTo(INSTANTS_BEFORE);
    }

    @Test
    void theOnlyNaiveTimestampsLeftAreTheOnesWithAReason() {
        Set<String> naive = new TreeSet<>(db.sql().queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
            + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND data_type = 'timestamp without time zone'", String.class));
        assertThat(naive).isEqualTo(TimestampColumns.LEFT_NAIVE.keySet());
    }

    @Test
    void theDashboardDayIndexIsOnTheChicagoDay() {
        String definition = db.sql().queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_job_queue_date_created_day'", String.class);
        assertThat(definition).contains("AT TIME ZONE 'America/Chicago'").contains("::date");
    }

    @Test
    void aMeterEventAndTheRunItBelongsToLandInTheSameMonth() {
        JdbcTemplate sql = db.sql();
        // meter.usage_event is billing_db's now (and was timestamptz all along); the event is spelled here as the
        // meter records it. 23:30Z on 31 January is 17:30 in Chicago -- the run the fixture stored at 17:30 naive.
        String join = "WITH event(occurred_at) AS (VALUES (timestamptz '2026-01-31 23:30:00+00')) "
            + "SELECT q.job_queue_id, to_char(date_trunc('month', e.occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS event_month, "
            + "to_char(date_trunc('month', q.start_time AT TIME ZONE 'UTC'), 'YYYY-MM') AS run_month, "
            + "(e.occurred_at AT TIME ZONE 'UTC')::date::text AS python_day "
            + "FROM event e JOIN job_queue q ON q.start_time = e.occurred_at";
        for (String zone : new String[] {"UTC", "America/Chicago", "Asia/Tokyo"}) {
            sql.execute("SET TIME ZONE '" + zone + "'");
            List<Map<String, Object>> joined = sql.queryForList(join);
            assertThat(joined).as("joined in a %s session", zone).hasSize(1);
            assertThat(joined.get(0).get("job_queue_id")).isEqualTo(900102L);
            assertThat(joined.get(0).get("event_month")).isEqualTo("2026-01");
            assertThat(joined.get(0).get("run_month")).isEqualTo("2026-01");
            // The Python side's own reading, (occurred_at at time zone 'UTC')::date, is untouched by any of this.
            assertThat(joined.get(0).get("python_day")).isEqualTo("2026-01-31");
        }
        sql.execute("RESET TIME ZONE");
    }

    @Test
    void aSessionThatNamesNoZoneReadsNaiveInputAsChicago() {
        JdbcTemplate sql = db.sql();
        // V101: the database's own default. psql, psycopg2 and pg_dump name no zone and get this one; pgjdbc names
        // the JVM's in its startup packet and is unaffected (it sends Timestamps with their offset anyway).
        List<String> settings = sql.queryForList("SELECT unnest(setconfig) FROM pg_db_role_setting s JOIN pg_database d "
            + "ON d.oid = s.setdatabase WHERE d.datname = current_database() AND s.setrole = 0", String.class);
        assertThat(settings).contains("TimeZone=America/Chicago");
        // Which is what such a session then does with a naive literal and with now():
        sql.execute("SET TIME ZONE 'America/Chicago'");
        sql.update("UPDATE job_queue SET prepared_at = '2026-01-15 08:00:00' WHERE job_queue_id = 900102");
        assertThat(utcText(sql, "job_queue", "prepared_at", "900102")).isEqualTo("2026-01-15T14:00:00.000000Z");
        Boolean nowIsNow = sql.queryForObject("SELECT abs(extract(epoch FROM (now() - clock_timestamp()))) < 60", Boolean.class);
        assertThat(nowIsNow).isTrue();
        // And what breaks: a writer that sets UTC and sends Chicago wall-clock text. None exists (job-search reports
        // through process's REST callbacks; its seed scripts write now()), but this is its failure, pinned.
        sql.execute("SET TIME ZONE 'UTC'");
        sql.update("UPDATE job_queue SET prepared_at = '2026-01-15 08:00:00' WHERE job_queue_id = 900102");
        assertThat(utcText(sql, "job_queue", "prepared_at", "900102")).isEqualTo("2026-01-15T08:00:00.000000Z");
        sql.update("UPDATE job_queue SET prepared_at = NULL WHERE job_queue_id = 900102");
        sql.execute("RESET TIME ZONE");
    }

    @Test
    void theRollbackRestoresEveryStoredStringByteForByteAndV100AppliesAgain() throws Exception {
        JdbcTemplate sql = db.sql();
        // V100 and everything applied after it (V101, and whatever later changesets another track adds).
        db.rollback(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted >= "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V100));
        try {
            this.assertRolledBack(sql);
        } finally {
            db.finish();
        }
        assertThat(TimestampColumns.dataType(sql, "job_queue", "start_time")).isEqualTo("timestamp with time zone");
        assertThat(utcText(sql, "scheduler", "next_run_at", "900301")).isEqualTo("2026-03-08T08:30:00.000000Z");
    }

    private void assertRolledBack(JdbcTemplate sql) {
        Map<String, String> storedAfterRollback = new TreeMap<>();
        for (Map.Entry<String, List<String>> table : TimestampColumns.CONVERTED.entrySet()) {
            for (String column : table.getValue()) {
                assertThat(TimestampColumns.dataType(sql, table.getKey(), column)).as(table.getKey() + "." + column)
                    .isEqualTo("timestamp without time zone");
                storedAfterRollback.put(table.getKey() + "." + column, TimestampColumns.storedChecksum(sql, table.getKey(), column));
            }
        }
        assertThat(storedAfterRollback).isEqualTo(STORED_BEFORE);
        // Row by row, for the reader: the gap row is 02:30 again, not 03:30.
        for (String[] row : FIXTURE) {
            String stored = sql.queryForObject(String.format("SELECT %s::text FROM public.%s WHERE %s", row[2], row[0],
                whereKey(sql, row[0], row[1])), String.class);
            assertThat(stored).as("%s.%s %s", row[0], row[2], row[1]).isEqualTo(row[3]);
        }
        assertThat(sql.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_job_queue_date_created_day'", String.class))
            .contains("date(date_created)");
        assertThat(sql.queryForObject("SELECT to_regclass('public.timestamptz_v100_unrepresentable') IS NULL", Boolean.class)).isTrue();
        assertThat(sql.queryForList("SELECT unnest(setconfig) FROM pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase "
            + "WHERE d.datname = current_database() AND s.setrole = 0", String.class)).doesNotContain("TimeZone=America/Chicago");
    }
}
