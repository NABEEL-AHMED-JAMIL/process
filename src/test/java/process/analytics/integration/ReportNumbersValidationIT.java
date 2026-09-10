package process.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.analytics.dto.QueryResultDto;
import process.model.enums.UserRole;
import process.security.TenantContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers the engine reports, checked against the file it read them from.
 *
 * <b>This is the test that distinguishes a working analytics module from a good-looking one.</b>
 * Every other check in this repository asks whether a chart rendered, whether a request was made,
 * whether a configuration round-tripped. None of them would notice if SUM(amount) were wrong.
 * A number nobody has independently computed is a number nobody has checked.
 *
 * <b>How the second opinion is produced.</b> The CSV is pulled out of MinIO through the platform's
 * own storage service and parsed here, by hand, in plain Java: a BufferedReader, a split on commas,
 * BigDecimal arithmetic. No DuckDB, no SQL, no shared code with the thing under test. When these
 * two agree on the sum of a quarter of a million decimals, they agree because the data says so and
 * not because they are the same code twice.
 *
 * <b>Decimal, not double.</b> The column is DECIMAL(12,2) and the file holds it as text. Summing
 * 250,000 of them as doubles accumulates error in the last places, and the disagreement that
 * follows would look exactly like an engine bug. BigDecimal removes that ambiguity from the test.
 *
 * <b>Streamed, not held.</b> The file is 31 MB and is read once, forward, accumulating totals --
 * because a validation that needs the whole dataset in the heap cannot be pointed at a bigger one,
 * and the interesting datasets are always bigger.
 *
 * @author Nabeel Ahmed
 */
class ReportNumbersValidationIT {

    private static final String CSV = SampleDataGeneratorIT.PREFIX + "/orders.csv";

    /** Column positions in the generated file. Asserted against the header before anything else. */
    private static final String[] COLUMNS = {
        "order_id", "ordered_at", "order_date", "category", "sub_category", "region", "status",
        "channel", "customer_id", "product_sku", "quantity", "unit_price", "amount", "rating",
        "processing_ms", "discount_pct",
    };

    private static final int CATEGORY = 3;
    private static final int REGION = 5;
    private static final int STATUS = 6;
    private static final int AMOUNT = 12;
    private static final int RATING = 13;

    private AnalyticsQueryService service;
    private DatasetResolver resolver;

    /** Everything the hand parse works out, in one pass. */
    private static final class Truth {
        long rows;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal minAmount;
        BigDecimal maxAmount;
        long ratingsPresent;
        long ratingSum;
        final Map<String, BigDecimal> amountByCategory = new TreeMap<String, BigDecimal>();
        final Map<String, Long> countByRegion = new TreeMap<String, Long>();
        final Map<String, Long> countByStatus = new TreeMap<String, Long>();
    }

    private static Truth truth;

    @BeforeEach
    void setUp() throws Exception {
        AnalyticsIntegrationEnvironment.requireMinio();
        this.service = AnalyticsIntegrationEnvironment.service();
        this.resolver = AnalyticsIntegrationEnvironment.resolverOver(
            AnalyticsIntegrationEnvironment.minioConnection());
        TenantContext.set(AnalyticsIntegrationEnvironment.TENANT_ID,
            UserRole.TENANT_USER.name(), 5001L, "reports-validation@example.test");

        if (truth == null) {
            truth = readTheFileByHand();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.service != null) {
            this.service.shutdown();
        }
    }

    /**
     * One forward pass over the CSV, computing everything the engine will later be asked for.
     *
     * Split on a plain comma, which is safe here and would not be in general: the generator writes
     * no quoted field and no embedded comma, and the header assertion below is what stops that
     * assumption outliving the file it was made about.
     */
    private static Truth readTheFileByHand() throws Exception {
        Truth found = new Truth();
        InputStream raw = AnalyticsIntegrationEnvironment.openObject(
            AnalyticsIntegrationEnvironment.minioConnection(), CSV);
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(raw, StandardCharsets.UTF_8), 1 << 16);
        try {
            String header = reader.readLine();
            assertThat(header).as("the file this test parses by position must have this header")
                .isEqualTo(String.join(",", COLUMNS));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cell = line.split(",", -1);
                found.rows++;

                BigDecimal amount = new BigDecimal(cell[AMOUNT]);
                found.totalAmount = found.totalAmount.add(amount);
                if (found.minAmount == null || amount.compareTo(found.minAmount) < 0) {
                    found.minAmount = amount;
                }
                if (found.maxAmount == null || amount.compareTo(found.maxAmount) > 0) {
                    found.maxAmount = amount;
                }

                String rating = cell[RATING];
                if (!rating.isEmpty()) {
                    found.ratingsPresent++;
                    found.ratingSum += Long.parseLong(rating);
                }

                String category = cell[CATEGORY];
                BigDecimal running = found.amountByCategory.get(category);
                found.amountByCategory.put(category,
                    running == null ? amount : running.add(amount));

                bump(found.countByRegion, cell[REGION]);
                bump(found.countByStatus, cell[STATUS]);
            }
        } finally {
            reader.close();
        }
        return found;
    }

    private static void bump(Map<String, Long> counts, String key) {
        Long current = counts.get(key);
        counts.put(key, current == null ? 1L : current + 1L);
    }

    // ---- the engine's answers ----------------------------------------------------------------

    private DatasetRef dataset() throws AnalyticsException {
        return this.resolver.resolve("analytics-it-minio", CSV);
    }

    private QueryResultDto ask(String sql) throws AnalyticsException {
        return this.service.query(this.dataset(), null, sql);
    }

    private String one(String sql) throws AnalyticsException {
        QueryResultDto answer = this.ask(sql);
        assertThat(answer.getRows()).as("%s returned nothing", sql).isNotEmpty();
        return answer.getRows().get(0).get(0);
    }

    /** field -> value, from a two-column grouped result. */
    private Map<String, String> grouped(String sql) throws AnalyticsException {
        Map<String, String> found = new HashMap<String, String>();
        for (List<String> row : this.ask(sql).getRows()) {
            found.put(row.get(0), row.get(1));
        }
        return found;
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- what is actually asserted ------------------------------------------------------------

    @Test
    void theRowCountMatchesTheFile() throws Exception {
        assertThat(Long.parseLong(one("SELECT COUNT(*) FROM dataset")))
            .as("the engine and the file disagree about how many rows there are")
            .isEqualTo(truth.rows);
        assertThat(truth.rows).as("the sample dataset should be big enough to mean something")
            .isEqualTo(250_000L);
    }

    @Test
    void theTotalRevenueMatchesToThePenny() throws Exception {
        // A quarter of a million decimals, added twice by unrelated code. This is the assertion
        // the whole file exists for.
        assertThat(money(one("SELECT SUM(amount) FROM dataset")))
            .isEqualByComparingTo(truth.totalAmount.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void minimumAndMaximumMatchTheFile() throws Exception {
        assertThat(money(one("SELECT MIN(amount) FROM dataset")))
            .isEqualByComparingTo(truth.minAmount);
        assertThat(money(one("SELECT MAX(amount) FROM dataset")))
            .isEqualByComparingTo(truth.maxAmount);
    }

    @Test
    void theAverageMatchesTheFileToTwoPlaces() throws Exception {
        // Divided here rather than compared to a stored mean, so the check is of the engine's
        // arithmetic and not of a number this test copied from it.
        BigDecimal expected = truth.totalAmount.divide(
            BigDecimal.valueOf(truth.rows), 2, RoundingMode.HALF_UP);
        assertThat(money(one("SELECT AVG(amount) FROM dataset"))).isEqualByComparingTo(expected);
    }

    @Test
    void revenueByCategoryMatchesTheFile_everyGroup() throws Exception {
        Map<String, String> engine =
            this.grouped("SELECT category, SUM(amount) FROM dataset GROUP BY category");

        assertThat(engine.keySet())
            .as("the engine found a different set of categories than the file holds")
            .containsExactlyInAnyOrderElementsOf(truth.amountByCategory.keySet());
        assertThat(truth.amountByCategory).hasSize(6);

        for (Map.Entry<String, BigDecimal> expected : truth.amountByCategory.entrySet()) {
            assertThat(money(engine.get(expected.getKey())))
                .as("SUM(amount) for %s", expected.getKey())
                .isEqualByComparingTo(expected.getValue().setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Test
    void countByRegionMatchesTheFile_everyGroup() throws Exception {
        Map<String, String> engine =
            this.grouped("SELECT region, COUNT(*) FROM dataset GROUP BY region");

        assertThat(truth.countByRegion).hasSize(6);
        long total = 0;
        for (Map.Entry<String, Long> expected : truth.countByRegion.entrySet()) {
            assertThat(Long.parseLong(engine.get(expected.getKey())))
                .as("COUNT(*) for region %s", expected.getKey())
                .isEqualTo(expected.getValue());
            total += expected.getValue();
        }
        // The groups must also account for every row: a GROUP BY that quietly drops a row is the
        // failure that per-group equality alone cannot see.
        assertThat(total).isEqualTo(truth.rows);
    }

    @Test
    void countByStatusMatchesTheFile_andTheTailIsNotEmpty() throws Exception {
        Map<String, String> engine =
            this.grouped("SELECT status, COUNT(*) FROM dataset GROUP BY status");

        assertThat(truth.countByStatus).hasSize(5);
        for (Map.Entry<String, Long> expected : truth.countByStatus.entrySet()) {
            assertThat(Long.parseLong(engine.get(expected.getKey())))
                .as("COUNT(*) for status %s", expected.getKey())
                .isEqualTo(expected.getValue());
        }
        // A status report whose rare outcomes never occur is a report with nothing to say.
        assertThat(truth.countByStatus.get("Refunded")).isGreaterThan(1000L);
        assertThat(truth.countByStatus.get("Cancelled")).isGreaterThan(1000L);
    }

    @Test
    void nullsAreCountedAsNullsRatherThanAsZero() throws Exception {
        // rating is absent for Cancelled and Pending. COUNT(rating) must therefore be smaller than
        // COUNT(*), and AVG(rating) must divide by the present ones -- treating a null as a zero
        // would drag the average down and nothing on a chart would show why.
        long present = Long.parseLong(one("SELECT COUNT(rating) FROM dataset"));
        assertThat(present).isEqualTo(truth.ratingsPresent);
        assertThat(present).isLessThan(truth.rows);

        BigDecimal expectedAverage = BigDecimal.valueOf(truth.ratingSum)
            .divide(BigDecimal.valueOf(truth.ratingsPresent), 4, RoundingMode.HALF_UP);
        assertThat(new BigDecimal(one("SELECT AVG(rating) FROM dataset"))
            .setScale(4, RoundingMode.HALF_UP))
            .isEqualByComparingTo(expectedAverage);
    }

    @Test
    void csvSumsMoneyAsAdoubleWhileParquetKeepsItExact() throws Exception {
        // A REAL FINDING, pinned here rather than rounded away.
        //
        // DuckDB's CSV sniffer types a column of "1999.20" as DOUBLE; Parquet carries the
        // DECIMAL(12,2) it was written with. So the same money, summed over the same 250,000
        // rows, comes back as 103909527.57999855 from the CSV and 103909527.58 from the Parquet.
        //
        // At this size the error is under two thousandths of a penny and rounding hides it. It
        // grows with the row count, and it is the reason a financial report over CSV should not
        // be trusted in its last places -- which nothing in the product currently says.
        //
        // The assertion is deliberately of the DIFFERENCE, not of a tolerance: a tolerance would
        // pass just as happily if the CSV path were changed to read DECIMAL, and that change is
        // the fix. This test failing is how anybody would find out it had been made.
        DatasetRef parquetRef = this.resolver.resolve("analytics-it-minio",
            SampleDataGeneratorIT.PREFIX + "/orders.parquet");
        String sql = "SELECT SUM(amount) FROM dataset";

        String fromCsv = this.ask(sql).getRows().get(0).get(0);
        String fromParquet = this.service.query(parquetRef, null, sql).getRows().get(0).get(0);

        assertThat(fromParquet).as("Parquet should carry the decimal it was written with")
            .isEqualTo(truth.totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        assertThat(fromCsv).as("if this now equals the Parquet figure, the CSV path started "
            + "reading DECIMAL -- that is the fix, and this test is where to record it")
            .isNotEqualTo(fromParquet);
        // Same number to the penny, which is what makes the difference invisible in a report.
        assertThat(money(fromCsv)).isEqualByComparingTo(money(fromParquet));
    }

    @Test
    void csvAndParquetAgreeOnEveryFigure() throws Exception {
        // The same question of the same data in two formats. A disagreement here means one of the
        // readers is wrong, and the module advertises Parquet as the faster choice -- fast at
        // being wrong is worse than slow.
        DatasetRef parquet = this.resolver.resolve("analytics-it-minio",
            SampleDataGeneratorIT.PREFIX + "/orders.parquet");

        String sql = "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount), COUNT(rating) "
            + "FROM dataset";
        List<String> fromCsv = this.ask(sql).getRows().get(0);
        List<String> fromParquet = this.service.query(parquet, null, sql).getRows().get(0);

        // Compared at the precision the DATA has -- two places -- because the CSV reader types
        // money as DOUBLE and the Parquet reader keeps DECIMAL. That difference is real and is
        // pinned by csvSumsMoneyAsAdoubleWhileParquetKeepsItExact above; comparing raw strings
        // here would just be that same finding a second time, and would hide any OTHER
        // disagreement behind it.
        List<String> normalisedCsv = new ArrayList<String>();
        List<String> normalisedParquet = new ArrayList<String>();
        for (int at = 0; at < fromCsv.size(); at++) {
            normalisedCsv.add(money(fromCsv.get(at)).toPlainString());
            normalisedParquet.add(money(fromParquet.get(at)).toPlainString());
        }
        assertThat(normalisedParquet).isEqualTo(normalisedCsv);
    }
}
