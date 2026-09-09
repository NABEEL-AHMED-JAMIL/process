package process.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The operational half of Analytics Studio: what it is configured to do, and what it will admit
 * to an operator.
 *
 * These assertions look small next to the lock-down and gate tests, and they cover the failures
 * that are hardest to notice rather than the most dramatic. A limit that quietly reverts to a
 * fail-open default, a feature switch nobody can find, a health check that turns a monitoring
 * poll into a bucket request -- none of those break a test that is looking at query results, and
 * all three are things somebody would have to be told about in production instead.
 *
 * The health assertions are mostly about what the indicator DOESN'T do. That is deliberate: a
 * health endpoint is unauthenticated at the liveness layer and polled on a timer, so every
 * capability it has is a capability an attacker has for free, on repeat.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsOperationsTest {

    private static final List<String> PROFILES = Arrays.asList(
        "application-dev.properties", "application-stage.properties", "application-prod.properties");

    // ---- configuration -------------------------------------------------------------------

    /**
     * Spec 14's configuration block, value for value.
     *
     * Declared elsewhere (ApplicationPropertiesDeclarationTest asserts every key is present in
     * every profile); this asserts what the keys SAY. Two of them shipped four and ten times
     * tighter than the spec for months without anything noticing, because a number that is merely
     * wrong breaks nothing -- it just quietly makes the product smaller than it was specified to
     * be.
     */
    @Test
    void everyProfileShipsTheConfigurationSpecFourteenAsksFor() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = load(profile);
            assertThat(defaultOf(properties, "analytics.enabled")).as(profile).isEqualTo("true");
            assertThat(defaultOf(properties, "analytics.query.timeout-seconds")).as(profile)
                .isEqualTo("120");
            assertThat(defaultOf(properties, "analytics.query.max-rows")).as(profile)
                .isEqualTo("100000");
            assertThat(defaultOf(properties, "analytics.query.max-concurrent")).as(profile)
                .isEqualTo("4");
            assertThat(defaultOf(properties, "analytics.profile.sample-rows")).as(profile)
                .isEqualTo("1000000");
            assertThat(defaultOf(properties, "analytics.benchmark.enabled")).as(profile)
                .isEqualTo("true");
            assertThat(defaultOf(properties, "analytics.parquet.conversion-enabled")).as(profile)
                .isEqualTo("true");
        }
    }

    /**
     * Every analytics limit stays an environment override rather than a committed constant.
     *
     * The reason is the row ceiling. 100,000 rows is what the spec asks for and it is also the
     * number most likely to need lowering on a container that turns out to be short of heap, so
     * an operator has to be able to change it without a release.
     */
    @Test
    void everyAnalyticsLimitCanBeOverriddenFromTheEnvironment() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = load(profile);
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.startsWith("analytics.")) {
                    continue;
                }
                assertThat(String.valueOf(entry.getValue()).trim()).as(profile + " -> " + key)
                    .matches("\\$\\{[A-Z0-9_]+:[^}]*\\}");
            }
        }
    }

    /**
     * A directly constructed AnalyticsLimits is switched ON.
     *
     * Not a style point. Every analytics test builds this bean with `new` and sets the fields it
     * cares about by reflection, which is the right shape for a value holder and means a boolean
     * nobody sets is false. A feature switch that defaults to false outside Spring would turn
     * analytics off in every one of those tests the moment a caller starts honouring it, and the
     * failure would look like a broken query rather than like a flag.
     */
    @Test
    void aDirectlyConstructedLimitsIsSwitchedOnRatherThanOff() {
        AnalyticsLimits limits = new AnalyticsLimits();
        assertThat(limits.isEnabled()).isTrue();
        assertThat(limits.isBenchmarkEnabled()).isTrue();
        assertThat(limits.isParquetConversionEnabled()).isTrue();
    }

    // ---- the feature switch --------------------------------------------------------------

    /**
     * Off is a sentence, not a missing route.
     *
     * The whole reason the flag is honoured at the API boundary rather than by putting
     * @ConditionalOnProperty on the controllers: a 404 tells a user their client is broken. This
     * asserts the refusal says what happened and who can undo it, because that is the difference
     * between an outage a user reports and one they escalate.
     */
    @Test
    void aSwitchedOffStudioRefusesInWordsAUserCanActOn() {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "enabled", false);

        assertThatThrownBy(limits::requireEnabled)
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("switched off")
            .hasMessageContaining("administrator");
    }

    @Test
    void aSwitchedOnStudioRefusesNothing() {
        assertThatCode(new AnalyticsLimits()::requireEnabled).doesNotThrowAnyException();
    }

    /**
     * The benchmark switch is separate from the studio switch, in both directions.
     *
     * An operator during an incident wants the load generator off and the feature on; nothing
     * else in the module lets them say that, because PLATFORM_ADMIN answers who may generate
     * load rather than whether this environment accepts it.
     */
    @Test
    void theBenchmarkCanBeSwitchedOffWithoutSwitchingTheStudioOff() throws AnalyticsException {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "benchmarkEnabled", false);

        limits.requireEnabled();
        assertThatThrownBy(limits::requireBenchmarkEnabled)
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("benchmark is switched off");
    }

    /** Parquet off still leaves the two formats that do not need the engine to write them. */
    @Test
    void refusingParquetNamesTheFormatsThatStillWork() {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "parquetConversionEnabled", false);

        assertThatThrownBy(limits::requireParquetConversionEnabled)
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("CSV")
            .hasMessageContaining("JSON");
    }

    // ---- configuration mistakes ------------------------------------------------------------

    /** A well-formed policy has nothing to report, which is what makes the list worth reading. */
    @Test
    void aWellFormedConfigurationReportsNoProblems() {
        assertThat(configured("512MB", 2, 4, 100000, 100, 120).configurationProblems()).isEmpty();
    }

    /**
     * The mistyped memory ceiling, which is the one that starts cleanly and then fails everything.
     *
     * DuckDbSessionFactory refuses to interpolate a value of the wrong shape, so "512 MB" with a
     * space throws on the first statement of every session. Until this was reported somewhere, an
     * operator's only symptom was that analytics was broken and nothing else was.
     */
    @Test
    void aMistypedMemoryLimitIsReportedBeforeAUserFindsIt() {
        List<String> problems = configured("512 MB", 2, 4, 100000, 100, 120).configurationProblems();
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("analytics.duckdb.memory-limit").contains("512MB");
    }

    /**
     * A timeout of zero is the one wrong value that REMOVES a bound.
     *
     * Every other mistake here makes analytics smaller. This one makes it unbounded, because
     * AnalyticsQueryService reads a non-positive timeout the way JDBC does -- as no limit -- and
     * schedules no watchdog at all. It is reported in those words rather than as "invalid".
     */
    @Test
    void aTimeoutOfZeroIsReportedAsNoTimeoutRatherThanAsAShortOne() {
        List<String> problems = configured("512MB", 2, 4, 100000, 100, 0).configurationProblems();
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("no timeout at all");
    }

    // ---- health: what it refuses to do -----------------------------------------------------

    /**
     * The indicator has no way to open an engine session, and that is structural.
     *
     * The module's central rule is that a path which opens its own DuckDB connection is a second
     * door beside the locked one, and a health endpoint is the worst possible place for one: it
     * is polled on a timer by something that never authenticated. Rather than trusting that
     * nobody adds the call later, the class is built without anything it could make the call
     * with -- and this asserts that, so adding the field is what fails rather than what ships.
     */
    @Test
    void theHealthIndicatorCannotOpenAnEngineSession() {
        for (Field field : AnalyticsHealthIndicator.class.getDeclaredFields()) {
            assertThat(field.getType()).as("field " + field.getName())
                .isNotEqualTo(DuckDbSessionFactory.class)
                .isNotEqualTo(Connection.class);
        }
        for (Constructor<?> constructor : AnalyticsHealthIndicator.class.getDeclaredConstructors()) {
            assertThat(Arrays.asList(constructor.getParameterTypes()))
                .as("constructor parameters")
                .doesNotContain(DuckDbSessionFactory.class, Connection.class);
        }
    }

    /**
     * A switched-off feature does not poll the database to say it is switched off.
     *
     * The mock is never touched at all, which is the assertion: an environment that turned
     * analytics off should stop paying for it, including the connection this check would
     * otherwise take from the pool on every poll.
     */
    @Test
    void aSwitchedOffStudioIsReportedWithoutTouchingTheDatabase() {
        DataSource dataSource = mock(DataSource.class);
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "enabled", false);

        Health health = new AnalyticsHealthIndicator(limits, dataSource).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabled", false)
            .containsEntry("state", "disabled");
        verifyNoInteractions(dataSource);
    }

    /** Storage is never reached from here, and the detail says why rather than claiming a pass. */
    @Test
    void storageIsReportedAsNotProbedRatherThanAsHealthy() throws SQLException {
        Health health = new AnalyticsHealthIndicator(readyLimits(), readyDataSource()).health();

        assertThat(String.valueOf(health.getDetails().get("storage")))
            .contains("not probed")
            .contains("credentials");
        assertThat(String.valueOf(health.getDetails().get("events"))).contains("not probed");
    }

    // ---- health: what it reports -------------------------------------------------------------

    @Test
    void aHealthyDeploymentReportsReady() throws SQLException {
        Health health = new AnalyticsHealthIndicator(readyLimits(), readyDataSource()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "ready");
        assertThat(health.getDetails()).doesNotContainKey("findings");
        assertThat(String.valueOf(health.getDetails().get("engine"))).contains("no session opened");
    }

    /**
     * The limits are in the detail because there is nowhere else to read them.
     *
     * /actuator/env is deliberately not exposed -- it would carry the signing key, the encryption
     * key and every stored credential -- so before this, "what row ceiling is that container
     * actually running with" could not be answered from outside the container at all. Details are
     * shown when-authorized, so this is not readable by the liveness probe.
     */
    @Test
    void theLimitsInForceAreReportedBecauseActuatorEnvIsNot() throws SQLException {
        Health health = new AnalyticsHealthIndicator(readyLimits(), readyDataSource()).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> inForce = (Map<String, Object>) health.getDetails().get("limits");
        assertThat(inForce).containsEntry("maxRows", 100000)
            .containsEntry("queryTimeoutSeconds", 120)
            .containsEntry("maxConcurrentQueries", 4)
            .containsEntry("duckdbMemoryLimit", "512MB");
    }

    /**
     * A database analytics cannot read makes analytics degraded, never the container unhealthy.
     *
     * The same call application.properties already made for the mail indicator, and for the same
     * reason: this JVM is also the ETL dispatcher, and cycling it because a side feature cannot
     * reach its audit table would be a worse outage than the one being reported. The finding is
     * in "state" and "findings", which is what a monitor should alert on.
     */
    @Test
    void aDatabaseThatCannotBeReadDegradesAnalyticsWithoutTakingTheContainerDown() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection pool exhausted"));

        Health health = new AnalyticsHealthIndicator(readyLimits(), dataSource).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "degraded");
        assertThat(health.getDetails().get("findings").toString()).contains("connection pool exhausted");
    }

    /**
     * A database with no analytics tables is named as a migration that has not run.
     *
     * The failure this catches is a jar pointed at a database Liquibase never ran on: every query
     * still succeeds, and every audit insert fails, so the module keeps working while recording
     * nothing. "Who read what, and when" is exactly the thing that must not go missing quietly.
     */
    @Test
    void aDatabaseWithoutTheAnalyticsTablesNamesTheMissingMigration() throws SQLException {
        DataSource dataSource = dataSourceWhereTablesExist(false);

        Health health = new AnalyticsHealthIndicator(readyLimits(), dataSource).health();

        assertThat(health.getDetails()).containsEntry("state", "degraded");
        assertThat(health.getDetails().get("findings").toString())
            .contains("analytics_query_run")
            .contains("migrations have not run");
    }

    /**
     * The catalogue is read once, not on every poll.
     *
     * A monitor polls health every few seconds for the life of the process. Liquibase does not
     * drop these tables, so a table seen once is a table that stays -- and re-reading pg_catalog
     * on a timer for an answer that cannot change is the kind of cost that only shows up as a
     * mystery in a connection-pool graph.
     */
    @Test
    void theSchemaCheckIsNotRepeatedOncePassed() throws SQLException {
        DataSource dataSource = dataSourceWhereTablesExist(true);
        AnalyticsHealthIndicator indicator = new AnalyticsHealthIndicator(readyLimits(), dataSource);

        indicator.health();
        indicator.health();
        indicator.health();

        verify(dataSource, times(1)).getConnection();
    }

    // ---- cleanup: the tripwire on a decision that was recorded, not forgotten ----------------

    /**
     * Nothing in the analytics package runs on a schedule, and that is load-bearing.
     *
     * V32__analytics_query.sql states in capitals that nothing prunes analytics_query_run and
     * gives the condition that would change it: "phase four's completed-query events, or anything
     * else that lets a machine issue queries on a schedule". Rows are written by a person pressing
     * Run, so the table grows at human speed and there is nothing abandoned to collect -- which is
     * why this module has no cleanup job rather than a cleanup job with nothing to do.
     *
     * This test is the tripwire on that reasoning. If analytics ever schedules work, the premise
     * is gone and the retention rule stops being optional; failing here is the reminder to make
     * that decision deliberately instead of inheriting it.
     */
    @Test
    void analyticsSchedulesNoWorkSoNothingAccumulatesUnwatched() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        Set<BeanDefinition> found = scanner.findCandidateComponents("process.analytics");
        assertThat(found).isNotEmpty();

        List<String> scheduled = new ArrayList<>();
        for (BeanDefinition definition : found) {
            // Without initialisation: loading DuckDbSessionFactory for real would run its static
            // block and unpack a native library, which is a lot of work to answer a question
            // about annotations.
            Class<?> type = Class.forName(definition.getBeanClassName(), false,
                AnalyticsOperationsTest.class.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    scheduled.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(scheduled).as(
            "analytics now schedules work, so the 'nothing prunes analytics_query_run' decision in "
                + "V32__analytics_query.sql has to be re-made: it rested on rows being written only "
                + "by a person pressing Run").isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static AnalyticsLimits readyLimits() {
        return configured("512MB", 2, 4, 100000, 100, 120);
    }

    private static AnalyticsLimits configured(String memoryLimit, int threads, int maxConcurrent,
        int maxRows, int previewPageSize, int timeoutSeconds) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "memoryLimit", memoryLimit);
        ReflectionTestUtils.setField(limits, "threads", threads);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", previewPageSize);
        ReflectionTestUtils.setField(limits, "timeoutSeconds", timeoutSeconds);
        return limits;
    }

    private static DataSource readyDataSource() throws SQLException {
        return dataSourceWhereTablesExist(true);
    }

    /** A pool whose catalogue does, or does not, know the analytics tables. */
    private static DataSource dataSourceWhereTablesExist(boolean present) throws SQLException {
        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(present);

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(tables);

        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static Properties load(String resource) throws IOException {
        InputStream stream = AnalyticsOperationsTest.class.getClassLoader()
            .getResourceAsStream(resource);
        assertThat(stream).as(resource + " is missing from the classpath").isNotNull();
        try {
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        } finally {
            stream.close();
        }
    }

    /**
     * The value a deployment gets when it sets no environment variable.
     *
     * Every analytics property is declared as ${VAR:default}, so the committed default is the
     * half after the colon -- and it is the half that matters, because it is what a deployment
     * that never heard of the variable actually runs with.
     */
    private static String defaultOf(Properties properties, String key) {
        String declared = properties.getProperty(key);
        assertThat(declared).as(key + " is not declared").isNotNull();
        String value = declared.trim();
        assertThat(value).as(key + " must stay an environment override")
            .matches("\\$\\{[A-Z0-9_]+:[^}]*\\}");
        return value.substring(value.indexOf(':') + 1, value.length() - 1);
    }
}
