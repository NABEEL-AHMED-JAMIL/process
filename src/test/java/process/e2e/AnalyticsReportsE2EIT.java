package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import process.model.enums.Status;
import process.model.pojo.AppUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import process.model.enums.UserRole;

/**
 * Five reports, twenty-seven widgets, over the 150,000-row benchmark fixture.
 *
 * <b>The module used rather than unit-tested.</b> Every widget's analysisConfig is posted to the
 * same /analyze endpoint the Canvas posts to, against the real object in MinIO, and has to come
 * back with rows. A saved analysis that cannot run is a tile that renders an error the first time
 * somebody opens the page, and no unit test can see it: they assert a configuration was stored,
 * and storing a configuration nobody executed is exactly the failure mode.
 *
 * The building machinery lives in ReportBuildingSupport, shared with the twenty-report catalogue
 * over the orders dataset. What is left here is the reports, and the claims made about them.
 *
 * @author Nabeel Ahmed
 */
public class AnalyticsReportsE2EIT extends ReportBuildingSupport {

    private static final String CONNECTION = "etl-bucket";
    private static final String DATASET = "analytics-benchmark/sales-10mb.csv";

    /** Only commits when explicitly asked. See seedTheFiveReports(). */
    private static final boolean SEEDING = Boolean.getBoolean("analytics.seed.reports");

    private static final List<String> BY_REGION = Arrays.asList("region");
    private static final List<String> BY_CUSTOMER = Arrays.asList("customer");
    private static final List<String> BY_DATE = Arrays.asList("booked_on");
    private static final List<String> WHOLE_FILE = new ArrayList<String>();

    private static Widget w(String title, String chart, Map<String, Object> config) {
        return new Widget(title, chart, config);
    }

    @BeforeEach
    void signIn() {
        // A platform admin, because the etl-bucket connection is platform-owned (null tenant) and
        // that is who reads it in practice -- the audit rows on the live database are all theirs.
        this.admin = this.newPlatformAdmin();
    }

    static List<Report> reports() {
        List<Report> all = new ArrayList<Report>();

        all.add(new Report("Sales by region",
            "Where the money comes from, six ways over the same five regions.",
            Arrays.asList(
                w("Revenue by region", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                w("Orders by region", "donut",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), null, null)),
                w("Average order value by region", "ranked",
                    analysis(BY_REGION, measure("AVERAGE", "amount"), null, null)),
                w("Median order value by region", "bar",
                    analysis(BY_REGION, measure("MEDIAN", "amount"), null, null)),
                w("Largest order by region", "table",
                    analysis(BY_REGION, measure("MAXIMUM", "amount"), null, null)),
                w("Smallest order by region", "table",
                    analysis(BY_REGION, measure("MINIMUM", "amount"), null, null)))));

        all.add(new Report("Customer performance",
            "Who buys, how often, and how much -- capped at the top ten so it stays readable.",
            Arrays.asList(
                w("Top 10 customers by revenue", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 10)),
                w("Top 10 customers by order count", "ranked",
                    analysis(BY_CUSTOMER, measure("COUNT_ROWS", null), null, 10)),
                w("Top 10 customers by average order", "bar",
                    analysis(BY_CUSTOMER, measure("AVERAGE", "amount"), null, 10)),
                w("How many customers there are", "table",
                    analysis(WHOLE_FILE, measure("DISTINCT_COUNT", "customer"), null, null)),
                w("Customers per region", "bar",
                    analysis(BY_REGION, measure("DISTINCT_COUNT", "customer"), null, null)))));

        all.add(new Report("Booking activity",
            "What the order book looks like over time, newest value first.",
            Arrays.asList(
                w("Revenue by booking date", "bar",
                    analysis(BY_DATE, measure("SUM", "amount"), null, 20)),
                w("Orders by booking date", "bar",
                    analysis(BY_DATE, measure("COUNT_ROWS", null), null, 20)),
                w("Average order value by date", "ranked",
                    analysis(BY_DATE, measure("AVERAGE", "amount"), null, 20)),
                w("Busiest booking dates", "ranked",
                    analysis(BY_DATE, measure("MAXIMUM", "amount"), null, 20)),
                w("Customers active per date", "bar",
                    analysis(BY_DATE, measure("DISTINCT_COUNT", "customer"), null, 20)))));

        Map<String, Object> highValue = clause("amount", "GT", "900");
        Map<String, Object> lowValue = clause("amount", "LT", "100");
        // IS_NOT_NULL, not IS_NULL. The first draft asked for orders with NO note and the run
        // assertion caught it: every row in this dataset carries one, so that tile would have
        // drawn an empty chart every time anybody opened the report. Completeness stated as a
        // number people can see is the useful version of the same question.
        Map<String, Object> hasNote = clause("note", "IS_NOT_NULL", null);

        all.add(new Report("Revenue quality",
            "The tails of the order book: the very large, the very small, and the incomplete.",
            Arrays.asList(
                w("High-value revenue by region (over 900)", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"), highValue, null)),
                w("High-value order count by region", "donut",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), highValue, null)),
                w("Low-value order count by region (under 100)", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), lowValue, null)),
                w("Top customers among high-value orders", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), highValue, 10)),
                w("Orders carrying a note, by region", "table",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), hasNote, null)))));

        all.add(new Report("Executive summary",
            "Six numbers for somebody who has ninety seconds.",
            Arrays.asList(
                w("Total revenue", "table",
                    analysis(WHOLE_FILE, measure("SUM", "amount"), null, null)),
                w("Total orders", "table",
                    analysis(WHOLE_FILE, measure("COUNT_ROWS", null), null, null)),
                w("Average order value", "table",
                    analysis(WHOLE_FILE, measure("AVERAGE", "amount"), null, null)),
                w("Distinct customers", "table",
                    analysis(WHOLE_FILE, measure("DISTINCT_COUNT", "customer"), null, null)),
                w("Revenue by region", "donut",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                w("Top 5 customers", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 5)))));

        return all;
    }

    // ---- what is actually asserted ----------------------------------------------------------

    @Test
    void everyWidgetOnEveryReportCanActuallyRun() throws Exception {
        assumeTrue(minioIsUp(),
            "MinIO and the sales-10mb.csv fixture are needed for this");
        this.useLocalConnection("etl-bucket");

        List<Report> all = reports();
        assertThat(all).hasSize(5);
        for (Report report : all) {
            assertThat(report.widgets.size())
                .as("%s should carry five or six widgets", report.name)
                .isBetween(5, 6);
        }

        int widgets = 0;
        for (Report report : all) {
            this.build(report, CONNECTION, DATASET, LOCAL_CONNECTION);
            widgets += report.widgets.size();
        }
        assertThat(widgets).isEqualTo(27);
    }

    @Test
    void areportComesBackWithItsWidgetsInOrder() throws Exception {
        // The half a saveDashboard test misses: a dashboard that stores its widgets and hands
        // them back unordered renders a different page every time it is opened.
        Report report = reports().get(0);
        long id = this.build(report, CONNECTION, DATASET, null);

        JsonNode found = this.fetch("/analyticsWorkspace.json/fetchDashboardById?analyticsDashboardId=" + id);
        assertThat(found.path("status").asText()).isEqualTo("SUCCESS");

        JsonNode widgets = found.path("data").path("widgets");
        assertThat(widgets.size()).isEqualTo(report.widgets.size());
        for (int at = 0; at < widgets.size(); at++) {
            assertThat(widgets.get(at).path("widgetTitle").asText())
                .isEqualTo(report.widgets.get(at).title);
        }
    }

    @Test
    void everySavedAnalysisKeepsAconfigurationThatStillParses() throws Exception {
        // A stored configuration is only worth having if it comes back as the same analysis. This
        // is the round trip a dashboard makes every time it is opened, since nothing is cached.
        Report report = reports().get(4);
        long dashboardId = this.build(report, CONNECTION, DATASET, null);

        JsonNode found = this.fetch("/analyticsWorkspace.json/fetchDashboardById?analyticsDashboardId=" + dashboardId);
        for (JsonNode widget : found.path("data").path("widgets")) {
            long analysisId = widget.path("analyticsAnalysisId").asLong();
            JsonNode analysis = this.fetch(
                "/analyticsWorkspace.json/fetchAnalysisById?analyticsAnalysisId=" + analysisId);
            JsonNode config = this.json.readTree(analysis.path("data").path("analysisConfig").asText());
            assertThat(config.has("measure")).as("a widget with no measure draws nothing").isTrue();
            assertThat(config.path("measure").path("aggregation").asText()).isNotEmpty();
        }
    }

    @Test
    void anotherWorkspaceCannotSeeTheseReports() throws Exception {
        // Built by a platform admin, so they land with a null tenant. That is the shared-catalogue
        // shape, and the thing worth checking is that a TENANT admin's own listing does not gain
        // somebody else's dashboards by way of it.
        long mine = this.build(reports().get(0), CONNECTION, DATASET, null);
        AppUser other = this.newUser(UserRole.TENANT_ADMIN,
            this.newTenant("other-workspace"));

        MvcResult result = this.mvc.perform(
                this.getAs(other, "/analyticsWorkspace.json/fetchDashboardById?analyticsDashboardId=" + mine))
            .andReturn();
        JsonNode answer = this.json.readTree(result.getResponse().getContentAsString());

        assertThat(answer.path("status").asText())
            .as("a tenant admin reached a platform dashboard: %s", answer.path("message").asText())
            .isNotEqualTo("SUCCESS");
    }

    // ---- seeding, which only happens when asked ---------------------------------------------

    /**
     * Writes the five reports for real and leaves them there.
     *
     * @Rollback(false) plus a system property, and both are load-bearing: the annotation alone
     * would make every build write twenty-seven rows into whatever database it was pointed at.
     */
    @Test
    @Rollback(false)
    void seedTheFiveReports() throws Exception {
        assumeTrue(SEEDING, "Run with -Danalytics.seed.reports=true to actually write these");

        // Owned by the platform admin who ALREADY exists, not by a fresh e2e-<uuid> user.
        // @Rollback(false) means whoever writes these rows is a row too, and seeding a workspace
        // with a junk account that owns everything in it is not a good trade for one line of setup.
        this.admin = this.appUserRepository
            .findByUsernameAndStatusNot("admin@platform.local", Status.Delete)
            .orElseThrow(() -> new IllegalStateException(
                "admin@platform.local is not in this database -- seed it, or seed the reports "
                    + "somewhere they will belong to somebody real."));

        // NOT run from here, deliberately. The configurations are proved runnable by
        // everyWidgetOnEveryReportCanActuallyRun against the same bucket and object; running them
        // here would need the localhost connection that test creates, and this method commits --
        // so that temporary row would outlive the run and leave a second way into the same store.
        List<Long> ids = new ArrayList<Long>();
        for (Report report : reports()) {
            ids.add(this.build(report, CONNECTION, DATASET, null));
        }
        System.out.println("[seed] five reports written, dashboard ids " + ids
            + ", owned by " + this.admin.getUsername());
    }
}
