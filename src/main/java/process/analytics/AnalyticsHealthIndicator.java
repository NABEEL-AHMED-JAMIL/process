package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an operator can be told about Analytics Studio without touching anybody's data.
 *
 * Registered as a plain HealthIndicator bean, so it appears as the "analytics" component of the
 * actuator health endpoint this application already exposes (application.properties exposes
 * health/info/metrics/prometheus and nothing else). No second mechanism, no analytics-only
 * endpoint: the spec asks for the existing one and the existing one is a bean.
 *
 * <b>Three rules decided what may be checked here, and each one rules something out.</b>
 *
 * (1) A health check must not spend a governor permit. There are four for the whole application
 * and a monitor polls on a timer, so a probe that took one would make the ceiling smaller for
 * users in exact proportion to how closely they were being watched.
 *
 * (2) A health check must not open its own engine session. That is the module's central rule --
 * a path that opens its own Connection is a second door beside the locked one -- and it does not
 * stop being true because the caller is a monitor. This class therefore has no DuckDbSessionFactory
 * field and no way to obtain one, which is asserted by a test rather than left to discipline.
 * What is checked instead is that the driver is loadable, which is the deployment question a
 * health endpoint can actually answer.
 *
 * (3) A health check must not probe object storage, and this is the one worth spelling out
 * because the spec explicitly asks for storage connectivity "only through safe checks". There is
 * no safe live probe available here. Every read of a bucket needs one tenant's decrypted
 * credentials, and a health poll has no caller -- so probing means the application acting as a
 * tenant that did not ask it to, signing a request on a timer, against a bucket named by stored
 * configuration. That turns a monitoring endpoint into a way to make this server reach arbitrary
 * object storage on demand, which is the shape of the cross-bucket failure StatementGate exists
 * to prevent. Storage is therefore reported as not probed, with the reason, rather than reported
 * as UP on the strength of a check nobody ran.
 *
 * <b>Why this never reports DOWN.</b> The same argument application.properties already makes for
 * the mail indicator, which is disabled there: a non-critical side feature must not flip the
 * container's aggregate status and get it cycled by whatever watches Docker health. Analytics
 * being misconfigured says nothing about the ETL dispatcher in the same JVM, and taking the
 * dispatcher down to report it would be a worse outage than the one being reported. The finding
 * is carried in a "state" detail of ready / degraded / disabled, which is what a monitor should
 * alert on. Details are shown when-authorized, so an unauthenticated liveness probe still sees
 * only {"status":"UP"} and none of the numbers below.
 *
 * @author Nabeel Ahmed
 */
@Component
public class AnalyticsHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsHealthIndicator.class);

    /** The class DuckDbSessionFactory loads at startup; present or the module cannot run at all. */
    private static final String DUCKDB_DRIVER = "org.duckdb.DuckDBDriver";

    /**
     * The tables analytics writes, and the reason this check is not a duplicate of "db".
     *
     * Spring's own DataSourceHealthIndicator already answers "is the database reachable". It
     * cannot answer the question that actually breaks analytics on a fresh deployment: whether
     * V32 and V33 ran on the database this jar was pointed at. Without them every query still
     * succeeds and every audit insert fails, so the module keeps working while quietly recording
     * nothing -- and "who read what, and when" is the one thing it must not lose silently.
     *
     * Only the tables analytics writes TODAY. A table listed here before its changeset ships would
     * report every deployment degraded for a migration nobody has applied yet, so this list grows
     * when the schema does, not in anticipation of it.
     */
    private static final String[] REQUIRED_TABLES = { "analytics_query_run", "analytics_benchmark_result" };

    /** How long the metadata lookup may take before it is treated as a failure, in seconds. */
    private static final int METADATA_TIMEOUT_SECONDS = 2;

    private final AnalyticsLimits limits;
    private final DataSource dataSource;

    /**
     * Set once the tables have been seen, and never cleared.
     *
     * A schema check is only interesting until it passes: Liquibase does not drop these tables,
     * so a table that existed at 09:00 exists at 17:00, and re-reading the catalogue every ten
     * seconds for the rest of the process's life buys nothing. Not volatile on purpose -- the
     * worst a stale read costs is one extra catalogue lookup, and the value only ever moves from
     * false to true.
     */
    private boolean schemaConfirmed;

    public AnalyticsHealthIndicator(AnalyticsLimits limits, DataSource dataSource) {
        this.limits = limits;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", this.limits.isEnabled());

        if (!this.limits.isEnabled()) {
            // A feature that is switched off must not poll the database to say so. This is the
            // one place in the module that honours analytics.enabled today, and it honours it by
            // doing less rather than by reporting less.
            details.put("state", "disabled");
            details.put("detail", "Analytics Studio is switched off by analytics.enabled, so "
                + "nothing was checked.");
            return Health.up().withDetails(details).build();
        }

        List<String> findings = new ArrayList<>(this.limits.configurationProblems());

        details.put("engine", this.engineCheck(findings));
        details.put("metadataDatabase", this.metadataCheck(findings));
        details.put("storage", "not probed -- a live check would need one tenant's credentials "
            + "and a health poll has no caller");
        details.put("events", "not probed -- the query-completed event goes to the broker "
            + "resolved for the caller's own tenant, so a poll with no caller has no broker to "
            + "check, and publishing is best-effort and never fails a query");
        details.put("limits", this.limitsInForce());

        details.put("state", findings.isEmpty() ? "ready" : "degraded");
        if (!findings.isEmpty()) {
            details.put("findings", findings);
        }
        // Always UP: see the class comment. The finding is in "state", not in the status.
        return Health.up().withDetails(details).build();
    }

    /**
     * Whether the engine could be opened, answered without opening one.
     *
     * Class.forName is the whole check, and it is deliberately the same call DuckDbSessionFactory
     * makes in its static block. It cannot tell us the native library will unpack -- that happens
     * on the first real connection -- and claiming otherwise would be exactly the kind of
     * unearned green this module's own gate rule refuses. So the detail says what was checked.
     */
    private String engineCheck(List<String> findings) {
        try {
            Class.forName(DUCKDB_DRIVER);
            return "driver present (" + DUCKDB_DRIVER + "); no session opened";
        } catch (ClassNotFoundException | LinkageError ex) {
            findings.add("The DuckDB driver is not loadable, so every analytics request will fail: "
                + ex.getMessage());
            return "driver missing";
        }
    }

    /**
     * Whether the analytics tables are on the database this application is actually pointed at.
     *
     * A catalogue lookup, not a query: getTables reads pg_catalog and never a row of anybody's
     * data, which is what makes it usable from an endpoint with no caller behind it. The
     * connection comes from the pool the rest of the application uses, so a pool that is
     * exhausted or a database that is down shows up here as the failure it is.
     */
    private String metadataCheck(List<String> findings) {
        if (this.schemaConfirmed) {
            return "analytics tables present";
        }
        try (Connection connection = this.dataSource.getConnection()) {
            if (!connection.isValid(METADATA_TIMEOUT_SECONDS)) {
                findings.add("The metadata database did not answer within "
                    + METADATA_TIMEOUT_SECONDS + "s, so analytics cannot record what it reads");
                return "unreachable";
            }
            List<String> missing = this.missingTables(connection.getMetaData());
            if (!missing.isEmpty()) {
                findings.add("The analytics migrations have not run on this database -- missing "
                    + String.join(", ", missing) + " -- so queries will run and no record of them "
                    + "will be kept");
                return "missing " + String.join(", ", missing);
            }
            this.schemaConfirmed = true;
            return "analytics tables present";
        } catch (SQLException ex) {
            // Warn rather than error: the database being unreachable is already the "db"
            // component's finding, said louder and by the indicator that owns it.
            logger.warn("Analytics health could not read the database catalogue: {}", ex.getMessage());
            findings.add("The metadata database could not be read: " + ex.getMessage());
            return "unreachable";
        }
    }

    /**
     * The required tables this database does not have.
     *
     * Both spellings are tried because identifier case is the database's decision, not ours:
     * Postgres folds an unquoted name to lower case and several other engines fold it to upper,
     * and a check that only knew one of them would report a missing table on a database that has
     * it.
     */
    private List<String> missingTables(DatabaseMetaData metaData) throws SQLException {
        List<String> missing = new ArrayList<>();
        for (String table : REQUIRED_TABLES) {
            if (!this.tableExists(metaData, table) && !this.tableExists(metaData, table.toUpperCase())) {
                missing.add(table);
            }
        }
        return missing;
    }

    private boolean tableExists(DatabaseMetaData metaData, String table) throws SQLException {
        try (ResultSet tables = metaData.getTables(null, null, table, new String[] { "TABLE" })) {
            return tables.next();
        }
    }

    /**
     * The governor's numbers, reported because there is nowhere else to read them.
     *
     * /actuator/env is deliberately not exposed (application.properties says why: it leaks the
     * signing key, the encryption key and every stored credential), so without this an operator
     * cannot find out what ceilings are in force on a running container short of reading the
     * deployment's environment. These six are the answer to "why did that query stop", and they
     * are the numbers a support conversation starts from.
     */
    private Map<String, Object> limitsInForce() {
        Map<String, Object> inForce = new LinkedHashMap<>();
        inForce.put("queryTimeoutSeconds", this.limits.getTimeoutSeconds());
        inForce.put("maxRows", this.limits.getMaxRows());
        inForce.put("maxConcurrentQueries", this.limits.getMaxConcurrentQueries());
        inForce.put("previewPageSize", this.limits.getPreviewPageSize());
        inForce.put("duckdbMemoryLimit", this.limits.getMemoryLimit());
        inForce.put("duckdbThreads", this.limits.getThreads());
        inForce.put("benchmarkEnabled", this.limits.isBenchmarkEnabled());
        inForce.put("parquetConversionEnabled", this.limits.isParquetConversionEnabled());
        return inForce;
    }
}
