package process.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.pojo.AnalyticsAnalysis;
import process.model.pojo.AnalyticsDashboard;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.pojo.AnalyticsDataset;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AnalyticsQueryRun;
import process.model.pojo.BenchmarkResult;

import javax.persistence.Column;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ddl-auto=validate trap, in a form that actually runs.
 *
 * Hibernate is configured {@code ddl-auto=update} in dev and would be {@code validate} anywhere
 * that takes its schema seriously. Under {@code validate}, a mapped column with no column behind
 * it does not fail a query -- it stops the application from STARTING, for every feature, with a
 * message about one field. Nothing in this repository finds that out before a deployment does:
 * the unit suites never open a database, and the end-to-end suites are {@code *IT.java}, which
 * Surefire's default includes do not match, so they have never run in a build.
 *
 * So this reads the mapping off the entity classes -- the same annotations Hibernate reads -- and
 * asks the running PostgreSQL whether each table, column and sequence is there. It is the cheapest
 * possible version of starting the application, and it fails in the place a person can fix rather
 * than at four in the morning.
 *
 * <b>Missing schema is a failure here, not a skip.</b> The skip in this package is for absent
 * INFRASTRUCTURE: no PostgreSQL to talk to at all. A PostgreSQL that answers and does not have a
 * table the code maps is not a missing dependency, it is the exact defect this file exists to
 * report -- and reporting it as a skip would leave the suite passing on precisely the database
 * where the application would refuse to boot.
 *
 * Nothing is written. Every statement here reads {@code information_schema}, so the suite is safe
 * against a shared development database and leaves no rows to clean up.
 *
 * @author Nabeel Ahmed
 */
class PostgresAnalyticsSchemaIntegrationTest {

    /**
     * Every entity the analytics module persists, named explicitly rather than found by scanning.
     *
     * A classpath scan would silently stop covering an entity somebody forgot to put in the right
     * package, which is the failure this test is for. A list has to be edited when a table is
     * added, and that edit is the point.
     */
    private static final Class<?>[] ANALYTICS_ENTITIES = {
        AnalyticsDataset.class,
        AnalyticsQuery.class,
        AnalyticsQueryRun.class,
        BenchmarkResult.class,
        AnalyticsAnalysis.class,
        AnalyticsDashboard.class,
        AnalyticsDashboardWidget.class
    };

    /** Which changeset creates each table, so a failure names the migration to run. */
    private static final Map<String, String> CREATED_BY = createdBy();

    private Connection database;

    @BeforeEach
    void setUp() throws Exception {
        AnalyticsIntegrationEnvironment.requirePostgres();
        this.database = AnalyticsIntegrationEnvironment.postgres();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.database != null) {
            this.database.close();
        }
    }

    /** Every analytics entity has a table, or the application will not start under validate. */
    @Test
    void everyAnalyticsEntityHasATableInThisDatabase() throws Exception {
        List<String> missing = new ArrayList<String>();
        for (Class<?> entity : ANALYTICS_ENTITIES) {
            String table = tableOf(entity);
            if (!this.tableExists(table)) {
                missing.add(table + " (" + entity.getSimpleName() + ", created by "
                    + CREATED_BY.get(table) + ")");
            }
        }
        assertThat(missing)
            .as("these tables are mapped by an entity and are not in %s. Under "
                + "spring.jpa.hibernate.ddl-auto=validate the application would refuse to start; "
                + "apply the changesets named beside each one",
                AnalyticsIntegrationEnvironment.POSTGRES_URL)
            .isEmpty();
    }

    /**
     * Every column an entity maps exists, with the name the entity spells.
     *
     * Checked only on tables that are there, so a missing table is reported once by the test
     * above rather than as a list of twelve missing columns that all have one cause.
     */
    @Test
    void everyMappedColumnExistsOnItsTable() throws Exception {
        List<String> missing = new ArrayList<String>();
        for (Class<?> entity : ANALYTICS_ENTITIES) {
            String table = tableOf(entity);
            if (!this.tableExists(table)) {
                continue;
            }
            Set<String> actual = this.columnsOf(table);
            for (String mapped : mappedColumns(entity)) {
                if (!actual.contains(mapped)) {
                    missing.add(table + "." + mapped + " (" + entity.getSimpleName() + ")");
                }
            }
        }
        assertThat(missing)
            .as("these columns are mapped by an entity and do not exist. This is the failure "
                + "ddl-auto=validate turns into a refusal to start the whole application")
            .isEmpty();
    }

    /**
     * Every sequence an entity generates its id from exists.
     *
     * A separate assertion because it fails differently and later: a table can be perfectly
     * correct while the sequence its @SequenceGenerator names was never created, and the first
     * anyone hears of it is an insert failing in production long after the deployment that
     * introduced it looked fine.
     */
    @Test
    void everySequenceAnEntityGeneratesFromExists() throws Exception {
        List<String> missing = new ArrayList<String>();
        for (Class<?> entity : ANALYTICS_ENTITIES) {
            for (String sequence : sequencesOf(entity)) {
                if (!this.sequenceExists(sequence)) {
                    missing.add(sequence + " (" + entity.getSimpleName() + ")");
                }
            }
        }
        assertThat(missing)
            .as("these sequences are named by a @SequenceGenerator and do not exist, so every "
                + "insert into the owning table would fail")
            .isEmpty();
    }

    /**
     * The suite is reading the database it thinks it is.
     *
     * Cheap, and it turns the one confusing failure mode -- a correct test pointed at an empty or
     * unrelated database, reporting every table as missing -- into a sentence that says so.
     */
    @Test
    void thisIsAnAnalyticsDatabaseAndNotAnEmptyOne() throws Exception {
        assertThat(this.tableExists("databasechangelog"))
            .as("%s has no Liquibase changelog table, so it is not a database this application "
                + "has ever migrated -- point the suite at the right one rather than reading "
                + "the table report below as schema drift",
                AnalyticsIntegrationEnvironment.POSTGRES_URL)
            .isTrue();
    }

    // ---- the database ------------------------------------------------------------------------

    private boolean tableExists(String table) throws Exception {
        PreparedStatement query = this.database.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() "
                + "AND lower(table_name) = ?");
        try {
            query.setString(1, table);
            ResultSet rows = query.executeQuery();
            return rows.next();
        } finally {
            query.close();
        }
    }

    private boolean sequenceExists(String sequence) throws Exception {
        PreparedStatement query = this.database.prepareStatement(
            "SELECT 1 FROM information_schema.sequences WHERE sequence_schema = current_schema() "
                + "AND lower(sequence_name) = ?");
        try {
            query.setString(1, sequence);
            ResultSet rows = query.executeQuery();
            return rows.next();
        } finally {
            query.close();
        }
    }

    private Set<String> columnsOf(String table) throws Exception {
        Set<String> columns = new LinkedHashSet<String>();
        PreparedStatement query = this.database.prepareStatement(
            "SELECT lower(column_name) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND lower(table_name) = ?");
        try {
            query.setString(1, table);
            ResultSet rows = query.executeQuery();
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        } finally {
            query.close();
        }
        return columns;
    }

    // ---- the mapping -------------------------------------------------------------------------

    private static String tableOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table == null || table.name().isEmpty()
            ? entity.getSimpleName().toLowerCase(Locale.ROOT)
            : table.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The columns Hibernate would look for, read the way Hibernate reads them.
     *
     * Associations are skipped because their column belongs to the owning side and is already
     * mapped there as a plain field -- {@code tenant} is a lazy @ManyToOne over the same
     * {@code tenant_id} that is mapped beside it -- so following both would report a column
     * twice and, on a @OneToMany, look for one that was never on this table at all.
     *
     * A field with no @Column falls back to camelCase-to-snake_case, which is Spring Boot's
     * default physical naming strategy. Falling back rather than skipping is deliberate: skipping
     * would mean a field somebody forgot to annotate is also a field this test silently stops
     * checking, which is the failure it exists to catch arriving through the back door.
     */
    private static Set<String> mappedColumns(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<String>();
        for (Class<?> type = entity; type != null && !Object.class.equals(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (field.isAnnotationPresent(Transient.class)
                    || field.isAnnotationPresent(ManyToOne.class)
                    || field.isAnnotationPresent(OneToMany.class)
                    || field.isAnnotationPresent(OneToOne.class)
                    || field.isAnnotationPresent(ManyToMany.class)) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                columns.add(column != null && !column.name().isEmpty()
                    ? column.name().toLowerCase(Locale.ROOT)
                    : snakeCase(field.getName()));
            }
        }
        return columns;
    }

    private static Set<String> sequencesOf(Class<?> entity) {
        Set<String> sequences = new LinkedHashSet<String>();
        for (Class<?> type = entity; type != null && !Object.class.equals(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                SequenceGenerator generator = field.getAnnotation(SequenceGenerator.class);
                if (generator != null && !generator.sequenceName().isEmpty()) {
                    sequences.add(generator.sequenceName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return sequences;
    }

    private static String snakeCase(String name) {
        StringBuilder snake = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isUpperCase(character) && index > 0) {
                snake.append('_');
            }
            snake.append(Character.toLowerCase(character));
        }
        return snake.toString();
    }

    private static Map<String, String> createdBy() {
        Map<String, String> changesets = new HashMap<String, String>();
        changesets.put("analytics_dataset", "V31.0-analytics-dataset");
        changesets.put("analytics_query", "V32.0-analytics-query");
        changesets.put("analytics_query_run", "V32.0-analytics-query");
        changesets.put("analytics_benchmark_result", "V33.0-analytics-benchmark");
        changesets.put("analytics_analysis", "V34.0-analytics-workspace");
        changesets.put("analytics_dashboard", "V34.0-analytics-workspace");
        changesets.put("analytics_dashboard_widget", "V34.0-analytics-workspace");
        return changesets;
    }
}
