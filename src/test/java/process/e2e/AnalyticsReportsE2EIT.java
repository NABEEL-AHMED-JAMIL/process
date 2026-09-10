package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AppUser;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Five reports, twenty-seven widgets, built and run against the real 150,000-row dataset.
 *
 * <b>This is the module used rather than unit-tested.</b> Every other suite proves a piece behaves;
 * this one builds what a person would actually build -- five dashboards a sales team might keep --
 * and then insists that every widget on them can be RUN. A saved analysis that cannot run is a
 * tile that renders an error the first time somebody opens the page, and nothing in the unit
 * suites can see it: they assert that a configuration was stored, and storing a configuration
 * nobody executed is exactly the failure mode.
 *
 * <b>What each widget is proved to do.</b> Its analysisConfig is posted to the same /analyze
 * endpoint the Canvas posts to, against etl-bucket/analytics-benchmark/sales-10mb.csv in MinIO, and
 * the answer has to carry rows. That is a real DuckDB scan of a real object per widget.
 *
 * <b>Rollback.</b> E2ESupport is @Transactional, so these tests leave nothing behind -- which is
 * right for a test and wrong for the request that prompted it. {@link #seedTheFiveReports()} is the
 * same five reports with @Rollback(false), and it runs only under
 * -Danalytics.seed.reports=true, so a build can never quietly write twenty-seven rows into
 * somebody's database.
 *
 * @author Nabeel Ahmed
 */
class AnalyticsReportsE2EIT extends E2ESupport {

    /**
     * The alias the SEEDED reports point at -- the real platform connection, which the deployed
     * application reads through the Docker network.
     */
    private static final String CONNECTION = "etl-bucket";

    /**
     * The alias the RUNNING tests point at, and why it is not the one above.
     *
     * Every storage_connection row in this database has endpoint http://host.docker.internal:9000.
     * That resolves inside a container and not on the host, so a test JVM running here cannot read
     * a single one of them -- confirmed: host.docker.internal does not resolve, localhost:9000
     * does. This is a property of how the stack is wired, not a defect.
     *
     * So the run tests create their own row pointing at localhost, inside the transaction that
     * rolls back. It reads the same bucket, the same object, through the same resolver, engine and
     * governor; only the hostname differs. The seeded reports keep the real alias, because they are
     * opened by the deployed application, for which host.docker.internal is correct.
     */
    private static final String LOCAL_CONNECTION = "reports-e2e-minio";
    private static final String DATASET = "analytics-benchmark/sales-10mb.csv";

    /** Only commits when explicitly asked. See the class comment. */
    private static final boolean SEEDING = Boolean.getBoolean("analytics.seed.reports");

    @Autowired private StorageConnectionRepository storageConnectionRepository;
    @Autowired private EncryptionUtil encryptionUtil;

    private final ObjectMapper json = new ObjectMapper();

    private AppUser admin;

    /**
     * Whether MinIO is answering on its usual port.
     *
     * A socket probe rather than a read: these tests need the object store, and a suite that fails
     * on a laptop with no Docker running is a suite people delete. The analytics integration suite
     * next door makes the same trade for the same reason, and this cannot borrow its helper --
     * that class is package-private to process.analytics.integration.
     */
    private static boolean minioIsUp() {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress("localhost", 9000), 1500);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }

    @BeforeEach
    void signIn() {
        // A platform admin, because the etl-bucket connection is platform-owned (null tenant) and
        // that is who reads it in practice -- the audit rows on the live database are all theirs.
        this.admin = this.newPlatformAdmin();
    }

    /**
     * A connection to the same MinIO, addressed the way this JVM can actually reach it.
     *
     * Platform-owned (null tenant) to match the connection it stands in for, so DatasetResolver's
     * ownership check sees exactly what it sees in production. Rolled back with everything else.
     */
    private void useLocalConnection() {
        StorageConnection local = new StorageConnection();
        local.setProvider(StorageProvider.MINIO);
        local.setAlias(LOCAL_CONNECTION);
        local.setConnectionName(LOCAL_CONNECTION);
        local.setBucketName("etl-bucket");
        local.setEndpoint("http://localhost:9000");
        local.setAccessKey("minioadmin");
        local.setSecretKeyEnc(this.encryptionUtil.encrypt("minioadmin123"));
        local.setStatus(Status.Active);
        local.setTenantId(null);
        local.setDateCreated(new java.sql.Timestamp(System.currentTimeMillis()));
        this.storageConnectionRepository.save(local);
    }

    // ---- the five reports -----------------------------------------------------------------

    /** One widget: a title, the chart it draws as, and the analysis behind it. */
    private static final class Widget {
        final String title;
        final String chart;
        final Map<String, Object> config;

        Widget(String title, String chart, Map<String, Object> config) {
            this.title = title;
            this.chart = chart;
            this.config = config;
        }
    }

    private static final class Report {
        final String name;
        final String description;
        final List<Widget> widgets;

        Report(String name, String description, List<Widget> widgets) {
            this.name = name;
            this.description = description;
            this.widgets = widgets;
        }
    }

    private static Map<String, Object> measure(String aggregation, String field) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aggregation", aggregation);
        if (field != null) {
            m.put("field", field);
        }
        return m;
    }

    private static Map<String, Object> analysis(List<String> dimensions, Map<String, Object> measure,
        Map<String, Object> filters, Integer topN) {

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimensions", dimensions);
        config.put("measure", measure);
        if (filters != null) {
            config.put("filters", filters);
        }
        if (topN != null) {
            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("limit", topN);
            limit.put("includeOther", true);
            config.put("topN", limit);
        }
        Map<String, Object> sort = new LinkedHashMap<>();
        sort.put("by", "MEASURE");
        sort.put("direction", "DESC");
        config.put("sort", sort);
        return config;
    }

    private static Map<String, Object> clause(String field, String operator, String value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", field);
        c.put("operator", operator);
        if (value != null) {
            c.put("value", value);
        }
        return c;
    }

    private static final List<String> BY_REGION = Arrays.asList("region");
    private static final List<String> BY_CUSTOMER = Arrays.asList("customer");
    private static final List<String> BY_DATE = Arrays.asList("booked_on");
    private static final List<String> WHOLE_FILE = new ArrayList<String>();

    /**
     * The five, written as a sales team would ask for them rather than as a feature matrix.
     *
     * Every dimension here is one the fixture actually has -- id, region, customer, amount,
     * booked_on, note -- and every high-cardinality dimension carries a Top-N, because "group by
     * customer" over fifty thousand customers is a chart nobody can read and a response nobody
     * should have to receive.
     */
    private static List<Report> reports() {
        List<Report> all = new ArrayList<Report>();

        all.add(new Report("Sales by region",
            "Where the money comes from, six ways over the same five regions.",
            Arrays.asList(
                new Widget("Revenue by region", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                new Widget("Orders by region", "donut",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), null, null)),
                new Widget("Average order value by region", "ranked",
                    analysis(BY_REGION, measure("AVERAGE", "amount"), null, null)),
                new Widget("Median order value by region", "bar",
                    analysis(BY_REGION, measure("MEDIAN", "amount"), null, null)),
                new Widget("Largest order by region", "table",
                    analysis(BY_REGION, measure("MAXIMUM", "amount"), null, null)),
                new Widget("Smallest order by region", "table",
                    analysis(BY_REGION, measure("MINIMUM", "amount"), null, null)))));

        all.add(new Report("Customer performance",
            "Who buys, how often, and how much -- capped at the top ten so it stays readable.",
            Arrays.asList(
                new Widget("Top 10 customers by revenue", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 10)),
                new Widget("Top 10 customers by order count", "ranked",
                    analysis(BY_CUSTOMER, measure("COUNT_ROWS", null), null, 10)),
                new Widget("Top 10 customers by average order", "bar",
                    analysis(BY_CUSTOMER, measure("AVERAGE", "amount"), null, 10)),
                new Widget("How many customers there are", "table",
                    analysis(WHOLE_FILE, measure("DISTINCT_COUNT", "customer"), null, null)),
                new Widget("Customers per region", "bar",
                    analysis(BY_REGION, measure("DISTINCT_COUNT", "customer"), null, null)))));

        all.add(new Report("Booking activity",
            "What the order book looks like over time, newest value first.",
            Arrays.asList(
                new Widget("Revenue by booking date", "bar",
                    analysis(BY_DATE, measure("SUM", "amount"), null, 20)),
                new Widget("Orders by booking date", "bar",
                    analysis(BY_DATE, measure("COUNT_ROWS", null), null, 20)),
                new Widget("Average order value by date", "ranked",
                    analysis(BY_DATE, measure("AVERAGE", "amount"), null, 20)),
                new Widget("Busiest booking dates", "ranked",
                    analysis(BY_DATE, measure("MAXIMUM", "amount"), null, 20)),
                new Widget("Customers active per date", "bar",
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
                new Widget("High-value revenue by region (over 900)", "bar",
                    analysis(BY_REGION, measure("SUM", "amount"), highValue, null)),
                new Widget("High-value order count by region", "donut",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), highValue, null)),
                new Widget("Low-value order count by region (under 100)", "bar",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), lowValue, null)),
                new Widget("Top customers among high-value orders", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), highValue, 10)),
                new Widget("Orders carrying a note, by region", "table",
                    analysis(BY_REGION, measure("COUNT_ROWS", null), hasNote, null)))));

        all.add(new Report("Executive summary",
            "Six numbers for somebody who has ninety seconds.",
            Arrays.asList(
                new Widget("Total revenue", "table",
                    analysis(WHOLE_FILE, measure("SUM", "amount"), null, null)),
                new Widget("Total orders", "table",
                    analysis(WHOLE_FILE, measure("COUNT_ROWS", null), null, null)),
                new Widget("Average order value", "table",
                    analysis(WHOLE_FILE, measure("AVERAGE", "amount"), null, null)),
                new Widget("Distinct customers", "table",
                    analysis(WHOLE_FILE, measure("DISTINCT_COUNT", "customer"), null, null)),
                new Widget("Revenue by region", "donut",
                    analysis(BY_REGION, measure("SUM", "amount"), null, null)),
                new Widget("Top 5 customers", "ranked",
                    analysis(BY_CUSTOMER, measure("SUM", "amount"), null, 5)))));

        return all;
    }

    // ---- the calls ------------------------------------------------------------------------

    private JsonNode post(String url, Map<String, Object> body) throws Exception {
        MvcResult result = this.mvc.perform(
                this.postAs(this.admin, url, this.json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
        return this.json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode get(String url) throws Exception {
        MvcResult result = this.mvc.perform(this.getAs(this.admin, url))
            .andExpect(status().isOk())
            .andReturn();
        return this.json.readTree(result.getResponse().getContentAsString());
    }

    /** Saves one analysis and answers with its id. */
    private long saveAnalysis(Widget widget) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("analysisName", widget.title);
        body.put("connectionAlias", CONNECTION);
        body.put("datasetPath", DATASET);
        body.put("visualizationType", widget.chart);
        body.put("analysisConfig", this.json.writeValueAsString(widget.config));

        JsonNode answer = this.post("/analyticsWorkspace.json/saveAnalysis", body);
        assertThat(answer.path("status").asText()).as(widget.title + ": " + answer.path("message"))
            .isEqualTo("SUCCESS");
        return answer.path("data").path("analyticsAnalysisId").asLong();
    }

    /** Runs one widget's analysis for real, and answers with how many groups came back. */
    private int runWidget(Widget widget, String connection) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(widget.config);
        request.put("connection", connection);
        request.put("path", DATASET);

        JsonNode answer = this.post("/analytics.json/analyze", request);
        assertThat(answer.path("status").asText())
            .as("%s could not run: %s", widget.title, answer.path("message").asText())
            .isEqualTo("SUCCESS");
        return answer.path("data").path("rows").size();
    }

    private long saveDashboard(Report report) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dashboardName", report.name);
        body.put("dashboardDescription", report.description);

        JsonNode answer = this.post("/analyticsWorkspace.json/saveDashboard", body);
        assertThat(answer.path("status").asText()).as(answer.path("message").asText())
            .isEqualTo("SUCCESS");
        return answer.path("data").path("analyticsDashboardId").asLong();
    }

    private void saveWidget(long dashboardId, long analysisId, Widget widget, int order)
        throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("analyticsDashboardId", dashboardId);
        body.put("analyticsAnalysisId", analysisId);
        body.put("widgetTitle", widget.title);
        body.put("visualizationType", widget.chart);
        body.put("displayOrder", order);

        JsonNode answer = this.post("/analyticsWorkspace.json/saveWidget", body);
        assertThat(answer.path("status").asText())
            .as("%s: %s", widget.title, answer.path("message").asText())
            .isEqualTo("SUCCESS");
    }

    /** Builds one report end to end and answers with its dashboard id. */
    private long build(Report report, String runAgainst) throws Exception {
        long dashboardId = this.saveDashboard(report);
        int order = 0;
        for (Widget widget : report.widgets) {
            long analysisId = this.saveAnalysis(widget);
            this.saveWidget(dashboardId, analysisId, widget, order++);
            if (runAgainst != null) {
                assertThat(this.runWidget(widget, runAgainst))
                    .as("%s produced no rows -- a widget that draws nothing", widget.title)
                    .isGreaterThan(0);
            }
        }
        return dashboardId;
    }

    // ---- what is actually asserted ----------------------------------------------------------

    @Test
    void everyWidgetOnEveryReportCanActuallyRun() throws Exception {
        assumeTrue(minioIsUp(),
            "MinIO and the sales-10mb.csv fixture are needed for this");
        this.useLocalConnection();

        List<Report> all = reports();
        assertThat(all).hasSize(5);
        for (Report report : all) {
            assertThat(report.widgets.size())
                .as("%s should carry five or six widgets", report.name)
                .isBetween(5, 6);
        }

        int widgets = 0;
        for (Report report : all) {
            this.build(report, LOCAL_CONNECTION);
            widgets += report.widgets.size();
        }
        assertThat(widgets).isEqualTo(27);
    }

    @Test
    void areportComesBackWithItsWidgetsInOrder() throws Exception {
        // The half a saveDashboard test misses: a dashboard that stores its widgets and hands
        // them back unordered renders a different page every time it is opened.
        Report report = reports().get(0);
        long id = this.build(report, null);

        JsonNode found = this.get("/analyticsWorkspace.json/fetchDashboardById?analyticsDashboardId=" + id);
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
        long dashboardId = this.build(report, null);

        JsonNode found = this.get("/analyticsWorkspace.json/fetchDashboardById?analyticsDashboardId=" + dashboardId);
        for (JsonNode widget : found.path("data").path("widgets")) {
            long analysisId = widget.path("analyticsAnalysisId").asLong();
            JsonNode analysis = this.get(
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
        long mine = this.build(reports().get(0), null);
        AppUser other = this.newUser(process.model.enums.UserRole.TENANT_ADMIN,
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
            ids.add(this.build(report, null));
        }
        System.out.println("[seed] five reports written, dashboard ids " + ids
            + ", owned by " + this.admin.getUsername());
    }
}
