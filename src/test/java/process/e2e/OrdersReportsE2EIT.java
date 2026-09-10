package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.Rollback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Twenty reports over the 250,000-row orders dataset, one per analytical use case.
 *
 * <b>The catalogue is the test.</b> Each report is a different shape of question -- no dimension,
 * one, two; count, sum, average, min, max, distinct; filtered, unfiltered, Top-N, Bottom-N;
 * time series and cross-tab -- and every widget on every one of them has to RUN against the real
 * object in MinIO and come back with rows. A configuration that stores cleanly and cannot execute
 * is a tile that renders an error the first time somebody opens the page, and it looks identical
 * in the database to one that works.
 *
 * <b>What this exercises that a unit test cannot.</b> 106 widgets is 106 real DuckDB scans of a
 * 31 MB object through the resolver, the statement gate, the governor and the analysis compiler.
 * Two-dimension groupings, ORDER BY on a dimension rather than a measure, ANDed filter groups,
 * Bottom-N by ascending sort, and DISTINCT_COUNT over a high-cardinality column are all here
 * because they are the combinations that a single-shape smoke test never reaches.
 *
 * @author Nabeel Ahmed
 */
class OrdersReportsE2EIT extends ReportBuildingSupport {

    private static final String CONNECTION = "etl-bucket";
    private static final String DATASET = "analytics-samples/orders.csv";

    private static final boolean SEEDING = Boolean.getBoolean("analytics.seed.reports");

    private static final List<String> WHOLE = new ArrayList<String>();
    private static final List<String> BY_CATEGORY = Arrays.asList("category");
    private static final List<String> BY_SUB = Arrays.asList("sub_category");
    private static final List<String> BY_REGION = Arrays.asList("region");
    private static final List<String> BY_STATUS = Arrays.asList("status");
    private static final List<String> BY_CHANNEL = Arrays.asList("channel");
    private static final List<String> BY_DAY = Arrays.asList("order_date");
    private static final List<String> BY_MONTH = Arrays.asList("order_month");
    private static final List<String> BY_WEEKDAY = Arrays.asList("order_weekday");
    private static final List<String> BY_HOUR = Arrays.asList("order_hour");
    private static final List<String> BY_QUARTER = Arrays.asList("order_quarter");
    private static final List<String> BY_CUSTOMER = Arrays.asList("customer_id");
    private static final List<String> BY_PRODUCT = Arrays.asList("product_sku");
    private static final List<String> BY_RATING = Arrays.asList("rating");
    private static final List<String> BY_QUANTITY = Arrays.asList("quantity");
    private static final List<String> CATEGORY_X_REGION = Arrays.asList("category", "region");
    private static final List<String> CATEGORY_X_STATUS = Arrays.asList("category", "status");
    private static final List<String> MONTH_X_CATEGORY = Arrays.asList("order_month", "category");
    private static final List<String> REGION_X_CHANNEL = Arrays.asList("region", "channel");

    private static Widget w(String title, String chart, Map<String, Object> config) {
        return new Widget(title, chart, config);
    }

    /** Ordered by the DIMENSION, which is what a time series needs and a ranking does not. */
    private static Map<String, Object> overTime(List<String> dimension,
        Map<String, Object> measure, Integer topN) {
        return analysis(dimension, measure, null, topN, "DIMENSION", "ASC");
    }

    /** Smallest first: the Bottom-N question, which no other report here asks. */
    private static Map<String, Object> smallestFirst(List<String> dimension,
        Map<String, Object> measure, Integer topN) {
        return analysis(dimension, measure, null, topN, "MEASURE", "ASC");
    }

    private static final Map<String, Object> COMPLETED = clause("status", "EQ", "Completed");
    private static final Map<String, Object> YEAR_2024 = clause("order_year", "EQ", "2024");
    private static final Map<String, Object> YEAR_2025 = clause("order_year", "EQ", "2025");

    static List<Report> catalogue() {
        List<Report> all = new ArrayList<Report>();

        all.add(new Report("01 Overall KPI summary",
            "The whole file in six numbers, every one of them over all 250,000 rows.",
            Arrays.asList(
                w("Total revenue", "table", analysis(WHOLE, measure("SUM", "amount"), null, null)),
                w("Total orders", "table", analysis(WHOLE, measure("COUNT_ROWS", null), null, null)),
                w("Average order value", "table",
                    analysis(WHOLE, measure("AVERAGE", "amount"), null, null)),
                w("Distinct customers", "table",
                    analysis(WHOLE, measure("DISTINCT_COUNT", "customer_id"), null, null)),
                w("Average rating", "table",
                    analysis(WHOLE, measure("AVERAGE", "rating"), null, null)),
                w("Average processing time (ms)", "table",
                    analysis(WHOLE, measure("AVERAGE", "processing_ms"), null, null)))));

        all.add(new Report("02 Daily trend",
            "Two years of days, in date order rather than by size.",
            Arrays.asList(
                w("Revenue by day", "bar", overTime(BY_DAY, measure("SUM", "amount"), 60)),
                w("Orders by day", "bar", overTime(BY_DAY, measure("COUNT_ROWS", null), 60)),
                w("Average order value by day", "bar",
                    overTime(BY_DAY, measure("AVERAGE", "amount"), 60)),
                w("Customers active by day", "bar",
                    overTime(BY_DAY, measure("DISTINCT_COUNT", "customer_id"), 60)),
                w("Busiest days by revenue", "ranked",
                    analysis(BY_DAY, measure("SUM", "amount"), null, 15)))));

        all.add(new Report("03 Monthly trend",
            "The same order book folded to months, which is the grain a business reads.",
            Arrays.asList(
                w("Revenue by month", "bar", overTime(BY_MONTH, measure("SUM", "amount"), 24)),
                w("Orders by month", "bar", overTime(BY_MONTH, measure("COUNT_ROWS", null), 24)),
                w("Average order value by month", "bar",
                    overTime(BY_MONTH, measure("AVERAGE", "amount"), 24)),
                w("Completed revenue by month", "bar",
                    analysis(BY_MONTH, measure("SUM", "amount"), COMPLETED, 24, "DIMENSION", "ASC")),
                w("Revenue by quarter", "donut",
                    overTime(BY_QUARTER, measure("SUM", "amount"), null)))));

        all.add(new Report("04 Category distribution",
            "Where six categories sit against each other on value and on volume.",
            Arrays.asList(
                w("Revenue share by category", "donut",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), null, null)),
                w("Order share by category", "donut",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null), null, null)),
                w("Average order value by category", "ranked",
                    analysis(BY_CATEGORY, measure("AVERAGE", "amount"), null, null)),
                w("Units sold by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "quantity"), null, null)),
                w("Customers reached by category", "bar",
                    analysis(BY_CATEGORY, measure("DISTINCT_COUNT", "customer_id"), null, null)))));

        all.add(new Report("05 Top 10 sub-categories",
            "The leaders out of twenty-four, by three different definitions of leading.",
            Arrays.asList(
                w("Top 10 by revenue", "ranked",
                    analysis(BY_SUB, measure("SUM", "amount"), null, 10)),
                w("Top 10 by order count", "ranked",
                    analysis(BY_SUB, measure("COUNT_ROWS", null), null, 10)),
                w("Top 10 by units sold", "ranked",
                    analysis(BY_SUB, measure("SUM", "quantity"), null, 10)),
                w("Top 10 by average order value", "ranked",
                    analysis(BY_SUB, measure("AVERAGE", "amount"), null, 10)),
                w("Top 10 by customers reached", "ranked",
                    analysis(BY_SUB, measure("DISTINCT_COUNT", "customer_id"), null, 10)))));

        all.add(new Report("06 Bottom 10 sub-categories",
            "The same twenty-four from the other end -- smallest first, which is a different query.",
            Arrays.asList(
                w("Bottom 10 by revenue", "ranked",
                    smallestFirst(BY_SUB, measure("SUM", "amount"), 10)),
                w("Bottom 10 by order count", "ranked",
                    smallestFirst(BY_SUB, measure("COUNT_ROWS", null), 10)),
                w("Bottom 10 by units sold", "ranked",
                    smallestFirst(BY_SUB, measure("SUM", "quantity"), 10)),
                w("Bottom 10 by average order value", "ranked",
                    smallestFirst(BY_SUB, measure("AVERAGE", "amount"), 10)),
                w("Lowest-rated sub-categories", "ranked",
                    smallestFirst(BY_SUB, measure("AVERAGE", "rating"), 10)))));

        all.add(new Report("07 Category against region",
            "Two dimensions at once, which is where a cross-tab earns its place.",
            Arrays.asList(
                w("Revenue by category and region", "table",
                    analysis(CATEGORY_X_REGION, measure("SUM", "amount"), null, null)),
                w("Orders by category and region", "table",
                    analysis(CATEGORY_X_REGION, measure("COUNT_ROWS", null), null, null)),
                w("Average order value by category and region", "table",
                    analysis(CATEGORY_X_REGION, measure("AVERAGE", "amount"), null, null)),
                w("Revenue by region and channel", "table",
                    analysis(REGION_X_CHANNEL, measure("SUM", "amount"), null, null)),
                w("Units by category and region", "table",
                    analysis(CATEGORY_X_REGION, measure("SUM", "quantity"), null, null)))));

        all.add(new Report("08 One dimension, every measure",
            "Region asked six ways, so the measures can be compared on identical groups.",
            Arrays.asList(
                w("Revenue by region", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                w("Orders by region", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), null, null)),
                w("Average order value by region", "ranked",
                    analysis(BY_REGION, measure("AVERAGE", "amount"), null, null)),
                w("Largest order by region", "table",
                    analysis(BY_REGION, measure("MAXIMUM", "amount"), null, null)),
                w("Smallest order by region", "table",
                    analysis(BY_REGION, measure("MINIMUM", "amount"), null, null)),
                w("Median order value by region", "bar",
                    analysis(BY_REGION, measure("MEDIAN", "amount"), null, null)))));

        all.add(new Report("09 Category against status",
            "Which categories carry the cancellations and refunds.",
            Arrays.asList(
                w("Orders by category and status", "table",
                    analysis(CATEGORY_X_STATUS, measure("COUNT_ROWS", null), null, null)),
                w("Revenue by category and status", "table",
                    analysis(CATEGORY_X_STATUS, measure("SUM", "amount"), null, null)),
                w("Cancelled orders by category", "bar",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null),
                        clause("status", "EQ", "Cancelled"), null)),
                w("Refunded value by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"),
                        clause("status", "EQ", "Refunded"), null)),
                w("Completed value by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), COMPLETED, null)))));

        all.add(new Report("10 Time against dimension",
            "A month-by-category grid: the two-dimension case where one of them is time.",
            Arrays.asList(
                w("Revenue by month and category", "table",
                    analysis(MONTH_X_CATEGORY, measure("SUM", "amount"), null, null)),
                w("Orders by month and category", "table",
                    analysis(MONTH_X_CATEGORY, measure("COUNT_ROWS", null), null, null)),
                w("Revenue by weekday", "bar",
                    overTime(BY_WEEKDAY, measure("SUM", "amount"), null)),
                w("Orders by hour of day", "bar",
                    overTime(BY_HOUR, measure("COUNT_ROWS", null), null)),
                w("Average order value by hour", "bar",
                    overTime(BY_HOUR, measure("AVERAGE", "amount"), null)))));

        all.add(new Report("11 Revenue summary",
            "Money only: totals, extremes and the middle, over the completed order book.",
            Arrays.asList(
                w("Completed revenue", "table",
                    analysis(WHOLE, measure("SUM", "amount"), COMPLETED, null)),
                w("Completed average order value", "table",
                    analysis(WHOLE, measure("AVERAGE", "amount"), COMPLETED, null)),
                w("Largest completed order", "table",
                    analysis(WHOLE, measure("MAXIMUM", "amount"), COMPLETED, null)),
                w("Smallest completed order", "table",
                    analysis(WHOLE, measure("MINIMUM", "amount"), COMPLETED, null)),
                w("Median completed order", "table",
                    analysis(WHOLE, measure("MEDIAN", "amount"), COMPLETED, null)),
                w("Completed revenue by category", "donut",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), COMPLETED, null)))));

        all.add(new Report("12 Transaction counts",
            "Counting rather than valuing, which ranks the categories differently.",
            Arrays.asList(
                w("Orders by channel", "donut",
                    analysis(BY_CHANNEL, measure("COUNT_ROWS", null), null, null)),
                w("Orders by status", "donut",
                    analysis(BY_STATUS, measure("COUNT_ROWS", null), null, null)),
                w("Orders by category", "bar",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null), null, null)),
                w("Orders by region", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), null, null)),
                w("Top 15 products by order count", "ranked",
                    analysis(BY_PRODUCT, measure("COUNT_ROWS", null), null, 15)))));

        all.add(new Report("13 Average, minimum and maximum",
            "The three that a single total hides, over the same groups.",
            Arrays.asList(
                w("Average order value by category", "bar",
                    analysis(BY_CATEGORY, measure("AVERAGE", "amount"), null, null)),
                w("Minimum order value by category", "table",
                    analysis(BY_CATEGORY, measure("MINIMUM", "amount"), null, null)),
                w("Maximum order value by category", "table",
                    analysis(BY_CATEGORY, measure("MAXIMUM", "amount"), null, null)),
                w("Average processing time by channel", "bar",
                    analysis(BY_CHANNEL, measure("AVERAGE", "processing_ms"), null, null)),
                w("Slowest processing by channel", "table",
                    analysis(BY_CHANNEL, measure("MAXIMUM", "processing_ms"), null, null)),
                w("Average rating by category", "ranked",
                    analysis(BY_CATEGORY, measure("AVERAGE", "rating"), null, null)))));

        all.add(new Report("14 Distribution",
            "Shapes rather than totals: how orders spread across ratings, quantities and hours.",
            Arrays.asList(
                w("Orders by rating", "bar", overTime(BY_RATING, measure("COUNT_ROWS", null), null)),
                w("Orders by quantity", "bar",
                    overTime(BY_QUANTITY, measure("COUNT_ROWS", null), null)),
                w("Revenue by quantity", "bar",
                    overTime(BY_QUANTITY, measure("SUM", "amount"), null)),
                w("Orders by hour of day", "bar",
                    overTime(BY_HOUR, measure("COUNT_ROWS", null), null)),
                w("Orders by weekday", "bar",
                    overTime(BY_WEEKDAY, measure("COUNT_ROWS", null), null)))));

        all.add(new Report("15 Regional analysis",
            "One region at a time, and the whole set side by side.",
            Arrays.asList(
                w("Revenue by region", "donut",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                w("North: revenue by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"),
                        clause("region", "EQ", "North"), null)),
                w("International: revenue by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"),
                        clause("region", "EQ", "International"), null)),
                w("Customers by region", "bar",
                    analysis(BY_REGION, measure("DISTINCT_COUNT", "customer_id"), null, null)),
                w("Average rating by region", "ranked",
                    analysis(BY_REGION, measure("AVERAGE", "rating"), null, null)))));

        all.add(new Report("16 Status analysis",
            "The five outcomes, and what the unhappy ones have in common.",
            Arrays.asList(
                w("Orders by status", "donut",
                    analysis(BY_STATUS, measure("COUNT_ROWS", null), null, null)),
                w("Value by status", "bar",
                    analysis(BY_STATUS, measure("SUM", "amount"), null, null)),
                w("Average order value by status", "ranked",
                    analysis(BY_STATUS, measure("AVERAGE", "amount"), null, null)),
                w("Cancellations by region", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null),
                        clause("status", "EQ", "Cancelled"), null)),
                w("Cancellations by channel", "bar",
                    analysis(BY_CHANNEL, measure("COUNT_ROWS", null),
                        clause("status", "EQ", "Cancelled"), null)))));

        all.add(new Report("17 Period comparison",
            "2024 against 2025, asked identically so the two answers are comparable.",
            Arrays.asList(
                w("2024 revenue by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), YEAR_2024, null)),
                w("2025 revenue by category", "bar",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), YEAR_2025, null)),
                w("2024 orders by region", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), YEAR_2024, null)),
                w("2025 orders by region", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), YEAR_2025, null)),
                w("2024 completed revenue", "table",
                    analysis(WHOLE, measure("SUM", "amount"), allOf(YEAR_2024, COMPLETED), null)),
                w("2025 completed revenue", "table",
                    analysis(WHOLE, measure("SUM", "amount"), allOf(YEAR_2025, COMPLETED), null)))));

        all.add(new Report("18 High-value orders",
            "The tail of the distribution, narrowed by an ANDed pair of conditions.",
            Arrays.asList(
                w("Orders over 1000 by category", "bar",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null),
                        clause("amount", "GT", "1000"), null)),
                w("Value over 1000 by region", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"),
                        clause("amount", "GT", "1000"), null)),
                w("Completed orders over 1000 by category", "bar",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null),
                        allOf(clause("amount", "GT", "1000"), COMPLETED), null)),
                w("Top 15 customers over 1000", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"),
                        clause("amount", "GT", "1000"), 15)),
                w("Discounted order count by category", "bar",
                    analysis(BY_CATEGORY, measure("COUNT_ROWS", null),
                        clause("discount_pct", "GT", "0"), null)))));

        all.add(new Report("19 Customer drill-down",
            "From the whole book down to one region's best customers, a step at a time.",
            Arrays.asList(
                w("Top 20 customers by revenue", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 20)),
                w("Top 20 customers by order count", "ranked",
                    analysis(BY_CUSTOMER, measure("COUNT_ROWS", null), null, 20)),
                w("Customers by region", "bar",
                    analysis(BY_REGION, measure("DISTINCT_COUNT", "customer_id"), null, null)),
                w("North: top 20 customers", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"),
                        clause("region", "EQ", "North"), 20)),
                w("Customer spend by category", "table",
                    analysis(CATEGORY_X_REGION, measure("AVERAGE", "amount"), null, null)))));

        all.add(new Report("20 Executive dashboard",
            "Six figures for somebody with ninety seconds, drawn from all 250,000 rows.",
            Arrays.asList(
                w("Total revenue", "table", analysis(WHOLE, measure("SUM", "amount"), null, null)),
                w("Total orders", "table", analysis(WHOLE, measure("COUNT_ROWS", null), null, null)),
                w("Distinct customers", "table",
                    analysis(WHOLE, measure("DISTINCT_COUNT", "customer_id"), null, null)),
                w("Revenue by category", "donut",
                    analysis(BY_CATEGORY, measure("SUM", "amount"), null, null)),
                w("Revenue by month", "bar", overTime(BY_MONTH, measure("SUM", "amount"), 24)),
                w("Top 10 customers", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 10)))));

        return all;
    }

    @BeforeEach
    void signIn() {
        this.admin = this.newPlatformAdmin();
    }

    // ---- what is asserted --------------------------------------------------------------------

    @Test
    void theCatalogueIsTwentyReportsOfFiveOrSixWidgets() {
        List<Report> all = catalogue();
        assertThat(all).hasSize(20);

        Set<String> names = new LinkedHashSet<String>();
        int widgets = 0;
        for (Report report : all) {
            assertThat(report.widgets.size()).as("%s", report.name).isBetween(5, 6);
            assertThat(names.add(report.name)).as("duplicate report name %s", report.name).isTrue();
            widgets += report.widgets.size();
        }
        // 106, counted rather than guessed: fourteen reports carry five widgets and six
        // carry six. Pinned so that adding a report without adding it to the catalogue
        // count, or losing one to a bad merge, is a failure rather than a shrug.
        assertThat(widgets).isEqualTo(106);
    }

    @Test
    void everyWidgetOnEveryReportRunsAgainstTheRealDataset() throws Exception {
        assumeTrue(minioIsUp(), "MinIO and the orders sample dataset are needed for this");
        this.useLocalConnection("etl-bucket");

        for (Report report : catalogue()) {
            this.build(report, CONNECTION, DATASET, LOCAL_CONNECTION);
        }
    }

    @Test
    void aTwoDimensionReportComesBackWithBothDimensions() throws Exception {
        // The shape a single-dimension smoke test never reaches: two grouping columns, which the
        // compiler has to put in the SELECT, the GROUP BY and the response's column list.
        assumeTrue(minioIsUp(), "MinIO is needed for this");
        this.useLocalConnection("etl-bucket");

        Map<String, Object> config =
            analysis(CATEGORY_X_REGION, measure("SUM", "amount"), null, null);
        JsonNode answer = this.post("/analytics.json/analyze", withDataset(config));

        assertThat(answer.path("status").asText()).isEqualTo("SUCCESS");
        List<String> columns = new ArrayList<String>();
        for (JsonNode column : answer.path("data").path("columns")) {
            columns.add(column.path("name").asText());
        }
        assertThat(columns).as("both dimensions must reach the answer")
            .contains("category", "region");
        // Six categories across six regions, so the cross-tab must be bigger than either alone.
        assertThat(answer.path("data").path("rows").size()).isGreaterThan(6);
    }

    @Test
    void bottomNreturnsTheSmallestRatherThanTheLargest() throws Exception {
        // Ascending sort on a measure. Nothing else in the catalogue would notice if the
        // direction were ignored -- every other ranking asks for the top.
        assumeTrue(minioIsUp(), "MinIO is needed for this");
        this.useLocalConnection("etl-bucket");

        JsonNode smallest = this.post("/analytics.json/analyze",
            withDataset(smallestFirst(BY_SUB, measure("SUM", "amount"), 5)));
        JsonNode largest = this.post("/analytics.json/analyze",
            withDataset(analysis(BY_SUB, measure("SUM", "amount"), null, 5)));

        double first = smallest.path("data").path("rows").get(0).get(1).asDouble();
        double top = largest.path("data").path("rows").get(0).get(1).asDouble();
        assertThat(first).as("bottom-N returned the top of the list").isLessThan(top);
    }

    @Test
    void anAndedFilterNarrowsMoreThanEitherHalf() throws Exception {
        // A group, not a clause. Spreading it or dropping half of it would still return rows, and
        // a report that quietly applies one of two filters is the hardest kind of wrong to see.
        assumeTrue(minioIsUp(), "MinIO is needed for this");
        this.useLocalConnection("etl-bucket");

        long both = count(allOf(YEAR_2024, COMPLETED));
        long yearOnly = count(YEAR_2024);
        long completedOnly = count(COMPLETED);

        assertThat(both).isLessThan(yearOnly);
        assertThat(both).isLessThan(completedOnly);
        assertThat(both).isGreaterThan(0);
    }

    private long count(Map<String, Object> filters) throws Exception {
        JsonNode answer = this.post("/analytics.json/analyze",
            withDataset(analysis(WHOLE, measure("COUNT_ROWS", null), filters, null)));
        assertThat(answer.path("status").asText())
            .as(answer.path("message").asText()).isEqualTo("SUCCESS");
        return answer.path("data").path("rows").get(0).get(0).asLong();
    }

    private Map<String, Object> withDataset(Map<String, Object> config) {
        Map<String, Object> request = new java.util.LinkedHashMap<String, Object>(config);
        request.put("connection", LOCAL_CONNECTION);
        request.put("path", DATASET);
        return request;
    }

    @Test
    @Rollback(false)
    void seedTheTwentyReports() throws Exception {
        assumeTrue(SEEDING, "Run with -Danalytics.seed.reports=true to actually write these");

        this.admin = this.appUserRepository
            .findByUsernameAndStatusNot("admin@platform.local", process.model.enums.Status.Delete)
            .orElseThrow(() -> new IllegalStateException("admin@platform.local is not here"));

        List<Long> ids = new ArrayList<Long>();
        for (Report report : catalogue()) {
            ids.add(this.build(report, CONNECTION, DATASET, null));
        }
        System.out.println("[seed] twenty reports written, dashboard ids " + ids);
    }
}
