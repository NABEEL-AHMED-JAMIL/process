package process.analytics.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generates the benchmark pair -- {@code analytics-benchmark/sales-10mb.csv} and its Parquet twin
 * -- and puts them in the bucket the application reads.
 *
 * <b>Why this exists.</b> PROGRESS records the pair as "generated and uploaded" with no generator
 * in the repository, so the first clean slate took it away and left the browser suites, the MinIO
 * integration tests and the five seeded reports pointing at an object nobody could recreate. The
 * shape below is the one those suites assert about, and it is written down here so that the
 * assertions and the data can be read side by side:
 *
 * <ul>
 *   <li>150,000 rows and six columns: an id, a region, a customer, an amount, a date, a note.</li>
 *   <li>Five regions of exactly 30,000 rows each -- a Canvas drill into one region must narrow the
 *       Data tab to 30,000 of 150,000, which is only a proof if the count is the whole region.</li>
 *   <li>Every customer exactly three times, so a search for one narrows to three rows.</li>
 *   <li>Amount from 0.00 to 999.99, and 999.99 exactly once, so a descending sort over the whole
 *       file puts a value on page one that page one alone does not hold.</li>
 *   <li>A year of dates, no nulls, no constant column: the Quality tab must find nothing.</li>
 * </ul>
 *
 * Deterministic, like the orders sample: every value is a function of the row number, so an
 * expected answer can be computed independently of the engine that is being measured.
 *
 * @author Nabeel Ahmed
 */
public class BenchmarkDataGeneratorIT {

    /** Only writes when asked. Ten megabytes into somebody's bucket is not a build step. */
    private static final boolean GENERATE = Boolean.getBoolean("analytics.generate.benchmark");

    static final String PREFIX = "analytics-benchmark";
    static final int ROWS = 150_000;

    static String relation(int rows) {
        return "SELECT i AS id, "
            + "(['north','south','east','west','central'])[(i % 5) + 1] AS region, "
            + "'cust-' || ((i * 7919) % 50000) AS customer, "
            + "CAST((i % 100000) / 100.0 AS DECIMAL(12,2)) AS amount, "
            // to_days rather than "+ (i % 365)": DuckDB 1.1.3 has no DATE + INTEGER operator, and
            // the cast back to DATE is because DATE + INTERVAL widens to TIMESTAMP.
            + "CAST(DATE '2024-01-01' + to_days(CAST(i % 365 AS INTEGER)) AS DATE) AS booked_on, "
            + "'order note ' || (i % 31) AS note "
            + "FROM range(0, " + rows + ") AS t(i)";
    }

    @Test
    void generateAndUploadTheBenchmarkPair(@TempDir File workspace) throws Exception {
        assumeTrue(GENERATE,
            "Run with -Danalytics.generate.benchmark=true to write 150,000 rows into the bucket");
        AnalyticsIntegrationEnvironment.requireMinio();

        String[] keys = AnalyticsIntegrationEnvironment.writeCsvAndParquetFixture(
            AnalyticsIntegrationEnvironment.minioConnection(), PREFIX, relation(ROWS), workspace,
            "sales-10mb");

        System.out.println("[benchmark] wrote " + ROWS + " rows to " + keys[0] + " and " + keys[1]);
    }
}
