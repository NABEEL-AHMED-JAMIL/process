package process.time;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.hibernate.Hibernate;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.pojo.UserPageAccess;
import process.util.BusinessTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Table;
import java.io.File;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-163 (section 7.6): every timestamptz column round-trips through the service boundary unchanged -- the check
 * that catches the Chicago/UTC split coming back.
 *
 * Every time attribute of every entity is read through JPA from a row holding a known instant, and written back
 * through JPA. A java.sql.Timestamp attribute must be that instant; a LocalDateTime attribute must be its Chicago
 * wall-clock reading (what the consoles show); written back, the column must hold the same instant again. The
 * suite's JVM runs on UTC, so an attribute that still goes through the JVM's zone is six hours out here.
 *
 * The instants are the before-fixture's, DST edges included: a LocalDateTime read from 07:30Z on 1 November
 * is 01:30, and written back is 07:30Z again -- the second 01:30, as V100 decided -- not 06:30Z.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class EntityTimeRoundTripPostgresTest {

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    /** table -> the entity mapped to it. */
    private static final Map<String, Class<?>> ENTITIES = new LinkedHashMap<>();
    /** "table.column" -> the fixture rows that set it: {key, instant}. */
    private static final Map<String, List<String[]>> SET = new LinkedHashMap<>();
    /** Written by a @PreUpdate on every update, whatever the attribute held. */
    private static final Set<String> STAMPED_ON_UPDATE = new HashSet<>(Arrays.asList(
        "Scheduler.dateUpdated", "TaskReference.updatedAt", "PipelineConfig.updatedAt"));

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("entity_time");
        jpa = new ScratchJpa(db);
        JdbcTemplate sql = db.jdbc();
        TimestamptzMigrationPostgresTest.seedBaseRows(sql, false);
        for (String[] row : TimestamptzMigrationPostgresTest.fixture()) {
            // lookup_data is retired and read-only since MIG-167 (V144); no entity maps it.
            if (!TimestampColumns.exists(sql, row[0], row[2]) || "lookup_data".equals(row[0])) {
                continue;
            }
            sql.update(String.format("UPDATE public.%s SET %s = ?::timestamptz WHERE %s", row[0], row[2],
                TimestamptzMigrationPostgresTest.whereKey(sql, row[0], row[1])), row[4]);
            SET.computeIfAbsent(row[0] + "." + row[2], k -> new ArrayList<>()).add(new String[] {row[1], row[4]});
        }
        // MIG-167's tables are born timestamptz (V141, V143), after V100, so the before-fixture cannot name them: a row
        // each, at the fixture's DST edges. Likewise V163's scheduler.paused_since.
        sql.update("INSERT INTO task_reference (id, tenant_id, kind, name, value) VALUES (900971, 900, 'HOME_PAGE', 'fixture', 'https://f.test')");
        sql.update("INSERT INTO pipeline_config (id, tenant_id, config_key, kind, value) VALUES (900981, 900, 'V100_FIXTURE', 'VALUE', 'x')");
        String[][] born = {
            {"task_reference", "900971", "created_at", "2026-11-01T07:30:00Z"},
            {"task_reference", "900971", "updated_at", "2026-03-08T08:30:00Z"},
            {"pipeline_config", "900981", "created_at", "2026-01-15T14:00:00Z"},
            {"pipeline_config", "900981", "updated_at", "2026-07-04T13:00:00Z"},
            {"pipeline_config", "900981", "value_set_at", "2026-01-16T05:45:10.123456Z"},
            // V163 (the workspace pause), born timestamptz too.
            {"scheduler", "900301", "paused_since", "2026-11-01T07:30:00.000001Z"}};
        for (String[] row : born) {
            sql.update(String.format("UPDATE public.%s SET %s = ?::timestamptz WHERE %s", row[0], row[2],
                TimestamptzMigrationPostgresTest.whereKey(sql, row[0], row[1])), row[3]);
            SET.computeIfAbsent(row[0] + "." + row[2], k -> new ArrayList<>()).add(new String[] {row[1], row[3]});
        }
        for (File source : new File("src/main/java/process/model/pojo").listFiles((dir, name) -> name.endsWith(".java"))) {
            Class<?> type = Class.forName("process.model.pojo." + source.getName().replace(".java", ""));
            if (type.getAnnotation(Entity.class) != null && type.getAnnotation(Table.class) != null) {
                ENTITIES.put(type.getAnnotation(Table.class).name(), type);
            }
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static List<Field> timeAttributes(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            Class<?> t = field.getType();
            if (t == Timestamp.class || t == Date.class || t == LocalDateTime.class || t == Instant.class) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    private static String column(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.name().isEmpty() ? column.name() : field.getName();
    }

    private static Object id(Class<?> type, String key) {
        if (type == UserPageAccess.class) {
            String[] parts = key.split("\\|");
            return new UserPageAccess.Key(Long.valueOf(parts[0]), parts[1]);
        }
        return Long.valueOf(key);
    }

    private static Instant stored(String table, String column, String key) {
        JdbcTemplate sql = db.jdbc();
        return sql.queryForObject(String.format("SELECT %s FROM public.%s WHERE %s", column, table,
            TimestamptzMigrationPostgresTest.whereKey(sql, table, key)), Timestamp.class).toInstant();
    }

    @Test
    void everyTimeAttributeOfEveryEntityIsExercised() {
        Set<String> unexercised = new TreeSet<>();
        ENTITIES.forEach((table, type) -> timeAttributes(type).forEach(field -> {
            if (!SET.containsKey(table + "." + column(field))) {
                unexercised.add(type.getSimpleName() + "." + field.getName() + " (" + table + "." + column(field) + ")");
            }
        }));
        assertThat(unexercised).as("entity time attributes the before-fixture sets no value for").isEmpty();
    }

    @Test
    void readingGivesTheInstantOrItsChicagoWallClock() {
        EntityManager em = jpa.sharedEntityManager();
        List<String> wrong = new ArrayList<>();
        jpa.transactions().execute(status -> {
            ENTITIES.forEach((table, type) -> timeAttributes(type).forEach(field -> {
                for (String[] row : SET.getOrDefault(table + "." + column(field), new ArrayList<>())) {
                    Object entity = Hibernate.unproxy(em.find(type, id(type, row[0])));
                    Instant instant = Instant.parse(row[1]);
                    try {
                        Object value = field.get(entity);
                        Object expected = field.getType() == LocalDateTime.class ? BusinessTime.wallClockOf(instant) : Timestamp.from(instant);
                        Object actual = value instanceof Timestamp ? Timestamp.from(((Timestamp) value).toInstant()) : value;
                        if (!expected.equals(actual)) {
                            wrong.add(type.getSimpleName() + "." + field.getName() + " " + row[0] + ": " + actual + ", expected " + expected);
                        }
                    } catch (IllegalAccessException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }));
            return null;
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    void writingItBackStoresTheSameInstant() {
        EntityManager em = jpa.sharedEntityManager();
        List<String> wrong = new ArrayList<>();
        ENTITIES.forEach((table, type) -> timeAttributes(type).forEach(field -> {
            for (String[] row : SET.getOrDefault(table + "." + column(field), new ArrayList<>())) {
                // Moved a day and flushed, then put back and flushed: both writes go through the binding under test.
                jpa.transactions().execute(status -> {
                    Object entity = Hibernate.unproxy(em.find(type, id(type, row[0])));
                    try {
                        Object value = field.get(entity);
                        field.set(entity, value instanceof LocalDateTime ? ((LocalDateTime) value).plusDays(1)
                            : new Timestamp(((Timestamp) value).getTime() + 86_400_000L));
                        em.flush();
                        field.set(entity, value);
                        em.flush();
                    } catch (IllegalAccessException ex) {
                        throw new IllegalStateException(ex);
                    }
                    return null;
                });
                Instant now = stored(table, column(field), row[0]);
                if (STAMPED_ON_UPDATE.contains(type.getSimpleName() + "." + field.getName())) {
                    // Scheduler.onUpdate stamps it; what must hold is that the stamp is now, as an instant.
                    if (Math.abs(Duration.between(now, Instant.now()).getSeconds()) > 60) {
                        wrong.add(type.getSimpleName() + "." + field.getName() + " " + row[0] + ": stamped " + now + ", not now");
                    }
                } else if (!now.equals(Instant.parse(row[1]))) {
                    wrong.add(type.getSimpleName() + "." + field.getName() + " " + row[0] + ": stored " + now + ", expected " + row[1]);
                }
            }
        }));
        assertThat(wrong).isEmpty();
    }
}
