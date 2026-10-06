package process.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.enums.Status;
import process.model.pojo.SourceTask;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task list's date range (POST /sourceTask.json/listSourceTask?startDate=&endDate=) filtered and sorted on
 * st.date_created, a column source_task never had -- not in the V50 baseline, not in etl_job -- so any range,
 * or a sort by created date, was a 500 ("column st.date_created does not exist"). V130 gives source_task the
 * column: timestamptz, stamped by the database on insert. A range means the tasks created on those days, the
 * day being Chicago's, as everywhere else in QueryService.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class SourceTaskDateCreatedPostgresTest {

    private static final String CHANGESET = "130.0-source-task-date-created";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** The defect as the console met it: any range at all on a database built from the changelog. */
    @Test
    void aRangeIsAQueryPostgresCanRun() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.build("st_created_run")) {
            JdbcTemplate sql = db.sql();
            this.taskType(sql);
            sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
                + "VALUES (7406, 't', 'Active', 7400, 2906)");

            String today = sql.queryForObject("SELECT to_char(now() AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD')", String.class);
            assertThat(this.ids(sql, today, today, null)).containsExactly(7406L);
            assertThat(this.count(sql, today, today)).isEqualTo(1L);
        }
    }

    @Test
    void aRangeListsTheTasksCreatedOnThoseChicagoDays() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.build("st_created")) {
            JdbcTemplate sql = db.sql();
            this.taskType(sql);
            // The session's zone must not move a task to another day: the range reads Chicago's.
            sql.execute("SET TIME ZONE 'UTC'");
            String task = "INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, "
                + "date_created) VALUES (?, 't', 'Active', 7400, 2906, ?::timestamptz)";
            sql.update(task, 7401L, "2026-09-22 03:30:00+00");   // 22:30 on the 21st in Chicago
            sql.update(task, 7402L, "2026-09-21 04:30:00+00");   // 23:30 on the 20th in Chicago
            sql.update(task, 7403L, "2026-09-21 15:00:00+00");   // 10:00 on the 21st in Chicago

            assertThat(this.ids(sql, "2026-09-21", "2026-09-21", null)).containsExactlyInAnyOrder(7401L, 7403L);
            assertThat(this.count(sql, "2026-09-21", "2026-09-21")).isEqualTo(2L);
            assertThat(this.ids(sql, "2026-09-21", null, null)).containsExactlyInAnyOrder(7401L, 7403L);
            assertThat(this.count(sql, "2026-09-21", null)).isEqualTo(2L);
            assertThat(this.ids(sql, null, "2026-09-20", null)).containsExactly(7402L);
            assertThat(this.count(sql, null, "2026-09-20")).isEqualTo(1L);
            // Sorting by created date is on the list's whitelist, and ran into the same missing column.
            assertThat(this.ids(sql, null, null, "st.date_created")).containsExactly(7402L, 7403L, 7401L);
        }
    }

    @Test
    void aTaskIsStampedWithTheInstantItWasCreated() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.build("st_created_stamp")) {
            JdbcTemplate sql = db.sql();
            this.taskType(sql);
            assertThat(sql.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'source_task' "
                + "AND column_name = 'date_created'", String.class)).isEqualTo("timestamp with time zone");

            sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
                + "VALUES (7404, 't', 'Active', 7400, 2906)");

            Timestamp stamped = sql.queryForObject("SELECT date_created FROM source_task WHERE task_detail_id = 7404", Timestamp.class);
            assertThat(Duration.between(stamped.toInstant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        }
    }

    /** The console's create goes through Hibernate, which must leave the column to the database, not write NULL. */
    @Test
    void aTaskSavedThroughTheApplicationIsStamped() throws Exception {
        try (ScratchPostgres db = ScratchPostgres.create("st_created_jpa"); ScratchJpa jpa = new ScratchJpa(db)) {
            JdbcTemplate sql = db.jdbc();
            this.taskType(sql);
            SourceTaskRepository tasks = jpa.repository(SourceTaskRepository.class);
            SourceTaskTypeRepository types = jpa.repository(SourceTaskTypeRepository.class);
            Long id = jpa.transactions().execute(status -> {
                SourceTask created = new SourceTask();
                created.setTaskName("claims");
                created.setTaskStatus(Status.Active);
                created.setTenantId(2906L);
                created.setSourceTaskType(types.findById(7400L).get());
                return tasks.save(created).getTaskDetailId();
            });

            Timestamp stamped = sql.queryForObject("SELECT date_created FROM source_task WHERE task_detail_id = ?", Timestamp.class, id);
            assertThat(stamped).isNotNull();
            assertThat(Duration.between(stamped.toInstant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        }
    }

    /**
     * Nothing recorded when a long-lived database's tasks were made, and V130 does not invent it: they stay NULL.
     * They are still listed with no range, and no range claims them.
     */
    @Test
    void tasksFromBeforeTheColumnHaveNoCreatedDate() throws Exception {
        try (ScratchEtlJob db = ScratchEtlJob.buildUpTo("st_created_old", CHANGESET)) {
            JdbcTemplate sql = db.sql();
            this.taskType(sql);
            sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
                + "VALUES (7405, 't', 'Active', 7400, 2906)");

            db.finish();

            assertThat(sql.queryForObject("SELECT date_created FROM source_task WHERE task_detail_id = 7405", Timestamp.class)).isNull();
            assertThat(this.ids(sql, null, null, null)).containsExactly(7405L);
            assertThat(this.ids(sql, "2000-01-01", "2100-01-01", null)).isEmpty();
            assertThat(this.count(sql, "2000-01-01", "2100-01-01")).isEqualTo(0L);
        }
    }

    private void taskType(JdbcTemplate sql) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2906, 'Active', 'CRT', 'Created')");
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (7400, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2906)");
    }

    /** The list's own SQL, as SourceTaskServiceImpl.listSourceTask runs it. */
    private List<Long> ids(JdbcTemplate sql, String startDate, String endDate, String columnName) {
        TenantContext.set(2906L, "TENANT_ADMIN", 42L, "ops@created.test");
        return sql.query(new QueryService().listSourceTaskQuery(false, startDate, endDate, columnName,
            columnName == null ? null : "asc", null), (row, i) -> row.getLong("task_detail_id"));
    }

    private Long count(JdbcTemplate sql, String startDate, String endDate) {
        TenantContext.set(2906L, "TENANT_ADMIN", 42L, "ops@created.test");
        return sql.queryForObject(new QueryService().listSourceTaskQuery(true, startDate, endDate, null, null, null), Long.class);
    }
}
