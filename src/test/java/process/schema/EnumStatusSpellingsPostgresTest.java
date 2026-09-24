package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 7 (2026-09-24), V164: every enum-backed status column accepts only its enum's exact spellings.
 *
 * 50 source_job rows spelt 'ACTIVE'/'INACTIVE' -- written straight into the database by old e2e seed scripts -- made
 * Hibernate throw "No enum constant process.model.enums.Status.ACTIVE" on every read of them, so even deleting those
 * jobs through the API answered 500. The database now refuses such a row at the INSERT, and V164 respelt what was
 * already there. Held to the entities: every @Enumerated(STRING) column in process.model.pojo has a CHECK whose
 * values are exactly its enum's constants, so a new enum constant or a new status column without a migration fails
 * here, not at somebody's read.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 */
class EnumStatusSpellingsPostgresTest {

    private static final String V164 = "/db/changelog/changelog-sets/V164.0-enum-status-spellings/V164__enum_status_spellings.sql";

    private static final Class<?>[] ENTITIES = {
        process.model.pojo.SourceJob.class, process.model.pojo.SourceTask.class, process.model.pojo.SourceTaskType.class,
        process.model.pojo.JobQueue.class, process.model.pojo.JobAuditLogs.class, process.model.pojo.Pipeline.class,
        process.model.pojo.KafkaConnectionProfile.class };

    /**
     * Entities with @Enumerated columns deliberately left out: their tables moved to identity_db (MIG-107) and Core's
     * copies refuse every write (identity_moved_read_only), so no new spelling can reach them here.
     */
    private static final Class<?>[] IDENTITYS = {
        process.model.pojo.PageAccessProfile.class, process.model.pojo.AppUser.class, process.model.pojo.Tenant.class };

    private static ScratchPostgres db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("enum_spellings");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    /** table.column -> the enum's constants, for every @Enumerated(STRING) field of the entities. */
    private static Map<String, List<String>> enumColumns() {
        Map<String, List<String>> out = new TreeMap<>();
        for (Class<?> entity : ENTITIES) {
            String table = entity.getAnnotation(Table.class).name();
            for (Field field : entity.getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                String name = column != null && !column.name().isEmpty() ? column.name()
                    : field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                out.put(table + "." + name, Arrays.stream(field.getType().getEnumConstants()).map(Object::toString)
                    .collect(Collectors.toList()));
            }
        }
        return out;
    }

    /** The values a column's V164 CHECK allows, read back from the catalogue. */
    private static List<String> checkValues(JdbcTemplate sql, String table, String column) {
        List<String> definitions = sql.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE conrelid = to_regclass(?) AND contype = 'c' AND conname = ?", String.class, table, "ck_" + table + "_" + column + "_enum");
        if (definitions.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        Matcher m = Pattern.compile("'([^']*)'").matcher(definitions.get(0));
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    /** Every entity with an @Enumerated column is either checked here or named as Identity's. */
    @Test
    void noEntityWithAnEnumColumnIsForgotten() throws Exception {
        List<Class<?>> known = new ArrayList<>(Arrays.asList(ENTITIES));
        known.addAll(Arrays.asList(IDENTITYS));
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
            new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(javax.persistence.Entity.class));
        for (org.springframework.beans.factory.config.BeanDefinition found : scanner.findCandidateComponents("process")) {
            Class<?> entity = Class.forName(found.getBeanClassName());
            boolean hasEnum = Arrays.stream(entity.getDeclaredFields()).anyMatch(f -> f.isAnnotationPresent(Enumerated.class));
            if (hasEnum) {
                assertThat(known).as(entity.getName() + " has an @Enumerated column; add it to V164 and ENTITIES").contains(entity);
            }
        }
    }

    @Test
    void everyEnumColumnOfTheEntitiesHasACheckOfExactlyItsEnumsSpellings() {
        JdbcTemplate sql = db.jdbc();
        Map<String, List<String>> columns = enumColumns();
        assertThat(columns).containsKeys("source_job.job_status", "source_task.task_status", "job_queue.status",
            "source_task_type.task_type_status", "pipeline.status");
        columns.forEach((key, constants) -> {
            String[] tc = key.split("\\.");
            assertThat(checkValues(sql, tc[0], tc[1])).as(key).isNotNull().containsExactlyInAnyOrderElementsOf(constants);
        });
    }

    @Test
    void anUpperCaseStatusIsRefusedAtTheInsert() {
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9701, 'Active', 'EN9701', 'Enum') "
            + "ON CONFLICT DO NOTHING");
        assertThatThrownBy(() -> sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9701, 9701, now(), 'Manual', 'seeded by hand', 'ACTIVE', 1)"))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_source_job_job_status_enum");
        sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9701, 9701, now(), 'Manual', 'seeded by hand', 'Active', 1)");
        assertThatThrownBy(() -> sql.update("UPDATE source_job SET job_status = 'INACTIVE' WHERE job_id = 9701"))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE source_job SET execution = 'AUTO' WHERE job_id = 9701"))
            .isInstanceOf(DataAccessException.class);
        sql.update("DELETE FROM source_job WHERE job_id = 9701");
    }

    private static String v164() throws Exception {
        try (InputStream in = EnumStatusSpellingsPostgresTest.class.getResourceAsStream(V164)) {
            assertThat(in).as(V164).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What the live database had: case-only misspellings are respelt, then constrained -- re-run over such rows. */
    @Test
    void theMigrationRespellsCaseOnlyMisspellingsThenConstrains() throws Exception {
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9702, 'Active', 'EN9702', 'Enum') "
            + "ON CONFLICT DO NOTHING");
        sql.execute("ALTER TABLE source_job DROP CONSTRAINT ck_source_job_job_status_enum");
        sql.execute("ALTER TABLE source_job DROP CONSTRAINT ck_source_job_execution_enum");
        sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) VALUES "
            + "(9702, 9702, now(), 'MANUAL', 'a', 'ACTIVE', 1), (9702, 9703, now(), 'Manual', 'b', 'INACTIVE', 1), "
            + "(9702, 9704, now(), 'Auto', 'c', 'delete', 1), (9702, 9705, now(), 'Auto', 'd', 'Active', 1)");

        sql.execute(v164());

        assertThat(sql.queryForList("SELECT job_status || '/' || execution FROM source_job WHERE job_id BETWEEN 9702 AND 9705 "
            + "ORDER BY job_id", String.class)).containsExactly("Active/Manual", "Inactive/Manual", "Delete/Auto", "Active/Auto");
        assertThat(checkValues(sql, "source_job", "job_status")).containsExactlyInAnyOrder("Inactive", "Active", "Delete");
        assertThat(checkValues(sql, "source_job", "execution")).containsExactlyInAnyOrder("Auto", "Manual");
        sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9702 AND 9705");
    }

    /** A value that is no spelling of the enum at all has no safe repair: the migration stops, naming it. */
    @Test
    void aValueMatchingNoSpellingStopsTheMigrationByName() throws Exception {
        JdbcTemplate sql = db.jdbc();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9706, 'Active', 'EN9706', 'Enum') "
            + "ON CONFLICT DO NOTHING");
        sql.execute("ALTER TABLE source_task_type DROP CONSTRAINT ck_source_task_type_task_type_status_enum");
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, task_type_status, tenant_id) "
            + "VALUES (9706, 'enum-test', 'd', 'topic=t&partitions=[*]', 'Paused', 9706)");
        try {
            assertThatThrownBy(() -> sql.execute(v164())).hasMessageContaining("source_task_type.task_type_status")
                .hasMessageContaining("Paused");
        } finally {
            sql.update("DELETE FROM source_task_type WHERE source_task_type_id = 9706");
            sql.execute(v164());
        }
        assertThat(checkValues(sql, "source_task_type", "task_type_status")).containsExactlyInAnyOrder("Inactive", "Active", "Delete");
    }
}
