package process.analytics.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generates a realistic orders dataset and puts it in the bucket the application reads.
 *
 * <b>Why generated rather than hand-written.</b> A reporting module tested against forty rows is
 * tested against nothing: every aggregate fits on one screen, every Top-N returns everything, no
 * grouping is expensive, and no distribution has a shape. This writes a quarter of a million rows
 * with fourteen columns and deliberate correlations between them.
 *
 * <b>Deterministic, and that is what makes validation possible.</b> Every value is a pure function
 * of the row number through {@code hash()}, so the expected answer to "what is SUM(amount) for
 * Electronics in the North" can be computed independently -- by different code, from the same
 * definition -- and compared with what the analytics engine returns. Random data could only ever
 * be checked against itself.
 *
 * <b>The correlations are the point.</b> Uniform noise across every column would make every chart
 * a flat line and every comparison meaningless. Here: unit price depends on category, so
 * Electronics revenue dwarfs Grocery on value while Grocery wins on volume; customer purchases are
 * Pareto-skewed, so Top-N is a real question; ratings are absent for orders that never completed;
 * processing time depends on channel and status; and the order rate has a weekday and a seasonal
 * shape, so a daily trend has something to show.
 *
 * Uploaded through the platform's own StorageService -- the same path a person uploading a file
 * takes -- so what the reports read is what the application would have stored.
 *
 * @author Nabeel Ahmed
 */
class SampleDataGeneratorIT {

    /** Only writes when asked. Twenty-five megabytes into somebody's bucket is not a build step. */
    private static final boolean GENERATE = Boolean.getBoolean("analytics.generate.sample");

    static final String PREFIX = "analytics-samples";
    static final int ROWS = 250_000;

    /**
     * The dataset, as one DuckDB relation.
     *
     * Six independent hash streams, so no two columns are accidentally correlated through sharing
     * a generator -- which would put a relationship in the data that nobody designed and that a
     * report would faithfully display.
     */
    static String relation(int rows) {
        return "WITH base AS ("
            + "  SELECT i AS n,"
            + "    hash(i * 2654435761) % 1000000 AS h1,"
            + "    hash(i * 40503 + 17) % 1000000 AS h2,"
            + "    hash(i * 2246822519 + 101) % 1000000 AS h3,"
            + "    hash(i * 3266489917 + 7) % 1000000 AS h4,"
            + "    hash(i * 668265263 + 31) % 1000000 AS h5,"
            + "    hash(i * 374761393 + 53) % 1000000 AS h6"
            + "  FROM range(0, " + rows + ") AS t(i)"
            + "), shaped AS ("
            + "  SELECT n, h1, h2, h3, h4, h5, h6,"
            // Category: six, deliberately uneven -- a pie chart of six equal slices says nothing.
            + "    CAST(CASE WHEN h1 % 100 < 22 THEN 1 WHEN h1 % 100 < 42 THEN 2"
            + "         WHEN h1 % 100 < 60 THEN 3 WHEN h1 % 100 < 76 THEN 4"
            + "         WHEN h1 % 100 < 90 THEN 5 ELSE 6 END AS INTEGER) AS cat_idx,"
            + "    CAST((h2 % 4) + 1 AS INTEGER) AS sub_idx,"
            // Region: six, weighted, so a regional report has a leader and a tail.
            + "    CAST(CASE WHEN h3 % 100 < 26 THEN 1 WHEN h3 % 100 < 48 THEN 2"
            + "         WHEN h3 % 100 < 66 THEN 3 WHEN h3 % 100 < 82 THEN 4"
            + "         WHEN h3 % 100 < 94 THEN 5 ELSE 6 END AS INTEGER) AS region_idx,"
            // Status: completed dominates, as it should; the tail is what a status report is for.
            + "    CAST(CASE WHEN h4 % 1000 < 702 THEN 1 WHEN h4 % 1000 < 824 THEN 2"
            + "         WHEN h4 % 1000 < 902 THEN 3 WHEN h4 % 1000 < 961 THEN 4"
            + "         ELSE 5 END AS INTEGER) AS status_idx,"
            + "    CAST(CASE WHEN h5 % 100 < 45 THEN 1 WHEN h5 % 100 < 80 THEN 2"
            + "         WHEN h5 % 100 < 95 THEN 3 ELSE 4 END AS INTEGER) AS channel_idx,"
            // Pareto-ish customers: a fifth of orders belong to a small, loyal set, so Top-N is a
            // real question rather than an arbitrary slice of a uniform crowd.
            + "    CASE WHEN h6 % 100 < 20 THEN (h6 % 400) ELSE (h6 % 12000) END AS customer_n,"
            // Two years of days, with a weekday and a seasonal shape.
            + "    (h1 * 7 + h4) % 730 AS day_n,"
            + "    CAST((h2 % 5) + 1 AS INTEGER) AS quantity_base"
            + "  FROM base"
            + ")"
            + "SELECT"
            + "  100000 + n AS order_id,"
            + "  CAST(CAST(DATE '2024-01-01' + to_days(CAST(day_n AS INTEGER)) AS TIMESTAMP)"
            + "    + to_hours(CAST(8 + (h5 % 12) AS BIGINT))"
            + "    + to_minutes(CAST(h6 % 60 AS BIGINT)) AS TIMESTAMP) AS ordered_at,"
            + "  CAST(DATE '2024-01-01' + to_days(CAST(day_n AS INTEGER)) AS DATE) AS order_date,"
            + "  (['Electronics','Apparel','Home & Garden','Grocery','Sports','Beauty'])[cat_idx]"
            + "    AS category,"
            + "  (["
            + "    'Laptops','Phones','Audio','Accessories',"
            + "    'Menswear','Womenswear','Footwear','Outerwear',"
            + "    'Furniture','Kitchen','Bedding','Garden',"
            + "    'Fresh Produce','Beverages','Snacks','Household',"
            + "    'Fitness','Outdoor','Team Sports','Cycling',"
            + "    'Skincare','Haircare','Fragrance','Cosmetics'"
            + "  ])[(cat_idx - 1) * 4 + sub_idx] AS sub_category,"
            + "  (['North','South','East','West','Central','International'])[region_idx] AS region,"
            + "  (['Completed','Shipped','Pending','Cancelled','Refunded'])[status_idx] AS status,"
            + "  (['Web','Mobile','Store','Partner'])[channel_idx] AS channel,"
            + "  'CUST-' || lpad(CAST(customer_n AS VARCHAR), 5, '0') AS customer_id,"
            + "  (['Electronics','Apparel','Home & Garden','Grocery','Sports','Beauty'])[cat_idx]"
            + "    || '-' || lpad(CAST((h3 % 40) + 1 AS VARCHAR), 3, '0') AS product_sku,"
            + "  CAST(quantity_base AS INTEGER) AS quantity,"
            // Unit price by category, so revenue and volume disagree -- which is the whole reason
            // a report shows both. Electronics: hundreds. Grocery: single figures.
            + "  CAST(ROUND((["
            + "    420.0, 48.0, 130.0, 7.5, 65.0, 24.0"
            + "  ])[cat_idx] * (0.55 + ((h5 % 900) / 1000.0)), 2) AS DECIMAL(12,2)) AS unit_price,"
            + "  CAST(ROUND(["
            + "    420.0, 48.0, 130.0, 7.5, 65.0, 24.0"
            + "  ][cat_idx] * (0.55 + ((h5 % 900) / 1000.0)) * quantity_base, 2)"
            + "    AS DECIMAL(12,2)) AS amount,"
            // Absent for orders that never completed -- nobody rates a cancelled order. This is
            // the null a completeness check is supposed to find.
            + "  CASE WHEN status_idx IN (3, 4) THEN NULL"
            + "       WHEN h6 % 100 < 52 THEN 5 WHEN h6 % 100 < 78 THEN 4"
            + "       WHEN h6 % 100 < 90 THEN 3 WHEN h6 % 100 < 97 THEN 2 ELSE 1 END AS rating,"
            // Store orders are handled fastest, partner slowest; a cancelled order stops early.
            + "  CAST(CASE WHEN status_idx = 4 THEN 200 + (h4 % 800)"
            + "            ELSE ([600, 900, 350, 2400])[channel_idx] + (h4 % 2200) END"
            + "       AS INTEGER) AS processing_ms,"
            + "  CASE WHEN h3 % 100 < 12 THEN CAST((h3 % 4 + 1) * 5 AS INTEGER) ELSE 0 END"
            + "    AS discount_pct"
            + " FROM shaped";
    }

    @Test
    void generateAndUploadTheSampleDataset(@TempDir File workspace) throws Exception {
        assumeTrue(GENERATE,
            "Run with -Danalytics.generate.sample=true to write 250,000 rows into the bucket");
        AnalyticsIntegrationEnvironment.requireMinio();

        String[] keys = AnalyticsIntegrationEnvironment.writeCsvAndParquetFixture(
            AnalyticsIntegrationEnvironment.minioConnection(), PREFIX, relation(ROWS), workspace,
            "orders");

        System.out.println("[sample] wrote " + ROWS + " rows to " + keys[0] + " and " + keys[1]);
    }
}
