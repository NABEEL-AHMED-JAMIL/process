package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AppUser;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.util.EncryptionUtil;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Arrays;

/**
 * The machinery for building reports through the real API, shared by every report catalogue.
 *
 * Extracted when the second catalogue arrived, rather than copied: a catalogue is a LIST, and two
 * lists that each carry their own copy of "how to save a widget" drift the moment one of them is
 * fixed. What is left in a subclass is the reports themselves and what is claimed about them.
 *
 * @author Nabeel Ahmed
 */
public abstract class ReportBuildingSupport extends E2ESupport {

    @Autowired protected StorageConnectionRepository storageConnectionRepository;
    @Autowired protected EncryptionUtil encryptionUtil;

    protected final ObjectMapper json = new ObjectMapper();

    protected AppUser admin;

    /**
     * The alias the RUN checks use, and why it is not the deployment's own.
     *
     * Every storage_connection row in this database has endpoint host.docker.internal:9000. That
     * resolves inside a container and not on the host, so a test JVM here cannot read one of them.
     * These tests create their own row pointing at localhost, inside the transaction that rolls
     * back -- same bucket, same object, same resolver, engine and governor; only the hostname
     * differs. Seeded reports keep the real alias, which is what the deployed application needs.
     */
    protected static final String LOCAL_CONNECTION = "reports-e2e-minio";

    /** A socket probe, so a laptop with no Docker running skips rather than fails. */
    protected static boolean minioIsUp() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("localhost", 9000), 1500);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }

    protected void useLocalConnection(String bucket) {
        StorageConnection local = new StorageConnection();
        local.setProvider(StorageProvider.MINIO);
        local.setAlias(LOCAL_CONNECTION);
        local.setConnectionName(LOCAL_CONNECTION);
        local.setBucketName(bucket);
        local.setEndpoint("http://localhost:9000");
        local.setAccessKey("minioadmin");
        local.setSecretKeyEnc(this.encryptionUtil.encrypt("minioadmin123"));
        local.setStatus(Status.Active);
        local.setTenantId(null);
        local.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.storageConnectionRepository.save(local);
    }

    // ---- the vocabulary a catalogue is written in --------------------------------------------

    /** One tile: a title, the chart it draws as, and the analysis behind it. */
    protected static final class Widget {
        final String title;
        final String chart;
        final Map<String, Object> config;

        Widget(String title, String chart, Map<String, Object> config) {
            this.title = title;
            this.chart = chart;
            this.config = config;
        }
    }

    protected static final class Report {
        final String name;
        final String description;
        final List<Widget> widgets;

        Report(String name, String description, List<Widget> widgets) {
            this.name = name;
            this.description = description;
            this.widgets = widgets;
        }
    }

    protected static Map<String, Object> measure(String aggregation, String field) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("aggregation", aggregation);
        if (field != null) {
            m.put("field", field);
        }
        return m;
    }

    protected static Map<String, Object> clause(String field, String operator, String value) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("field", field);
        c.put("operator", operator);
        if (value != null) {
            c.put("value", value);
        }
        return c;
    }

    /** An AND of several conditions, which is how a report narrows on more than one thing. */
    protected static Map<String, Object> allOf(Map<String, Object>... clauses) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("op", "AND");
        group.put("clauses", new ArrayList<Map<String, Object>>(Arrays.asList(clauses)));
        return group;
    }

    protected static Map<String, Object> analysis(List<String> dimensions,
        Map<String, Object> measure, Map<String, Object> filters, Integer topN,
        String sortBy, String direction) {

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("dimensions", dimensions);
        config.put("measure", measure);
        if (filters != null) {
            config.put("filters", filters);
        }
        if (topN != null) {
            Map<String, Object> limit = new LinkedHashMap<String, Object>();
            limit.put("limit", topN);
            limit.put("includeOther", true);
            config.put("topN", limit);
        }
        Map<String, Object> sort = new LinkedHashMap<String, Object>();
        sort.put("by", sortBy);
        sort.put("direction", direction);
        config.put("sort", sort);
        return config;
    }

    /** The common case: biggest measure first. */
    protected static Map<String, Object> analysis(List<String> dimensions,
        Map<String, Object> measure, Map<String, Object> filters, Integer topN) {
        return analysis(dimensions, measure, filters, topN, "MEASURE", "DESC");
    }

    // ---- the calls ---------------------------------------------------------------------------

    protected JsonNode post(String url, Map<String, Object> body) throws Exception {
        MvcResult result = this.mvc.perform(
                this.postAs(this.admin, url, this.json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
        return this.json.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode fetch(String url) throws Exception {
        MvcResult result = this.mvc.perform(this.getAs(this.admin, url))
            .andExpect(status().isOk())
            .andReturn();
        return this.json.readTree(result.getResponse().getContentAsString());
    }

    protected long saveAnalysis(Widget widget, String connection, String dataset) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("analysisName", widget.title);
        body.put("connectionAlias", connection);
        body.put("datasetPath", dataset);
        body.put("visualizationType", widget.chart);
        body.put("analysisConfig", this.json.writeValueAsString(widget.config));

        JsonNode answer = this.post("/analyticsWorkspace.json/saveAnalysis", body);
        assertThat(answer.path("status").asText())
            .as("%s: %s", widget.title, answer.path("message").asText()).isEqualTo("SUCCESS");
        return answer.path("data").path("analyticsAnalysisId").asLong();
    }

    /** Runs one widget's analysis for real, and answers with how many groups came back. */
    protected int runWidget(Widget widget, String connection, String dataset) throws Exception {
        Map<String, Object> request = new LinkedHashMap<String, Object>(widget.config);
        request.put("connection", connection);
        request.put("path", dataset);

        JsonNode answer = this.post("/analytics.json/analyze", request);
        assertThat(answer.path("status").asText())
            .as("%s could not run: %s", widget.title, answer.path("message").asText())
            .isEqualTo("SUCCESS");
        return answer.path("data").path("rows").size();
    }

    protected long saveDashboard(Report report) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dashboardName", report.name);
        body.put("dashboardDescription", report.description);

        JsonNode answer = this.post("/analyticsWorkspace.json/saveDashboard", body);
        assertThat(answer.path("status").asText())
            .as(answer.path("message").asText()).isEqualTo("SUCCESS");
        return answer.path("data").path("analyticsDashboardId").asLong();
    }

    protected void saveWidget(long dashboardId, long analysisId, Widget widget, int order)
        throws Exception {

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("analyticsDashboardId", dashboardId);
        body.put("analyticsAnalysisId", analysisId);
        body.put("widgetTitle", widget.title);
        body.put("visualizationType", widget.chart);
        body.put("displayOrder", order);

        JsonNode answer = this.post("/analyticsWorkspace.json/saveWidget", body);
        assertThat(answer.path("status").asText())
            .as("%s: %s", widget.title, answer.path("message").asText()).isEqualTo("SUCCESS");
    }

    /**
     * Builds one report end to end.
     *
     * @param runAgainst the connection alias to RUN each widget against, or null to save only.
     */
    protected long build(Report report, String storeAgainst, String dataset, String runAgainst)
        throws Exception {

        long dashboardId = this.saveDashboard(report);
        int order = 0;
        for (Widget widget : report.widgets) {
            long analysisId = this.saveAnalysis(widget, storeAgainst, dataset);
            this.saveWidget(dashboardId, analysisId, widget, order++);
            if (runAgainst != null) {
                assertThat(this.runWidget(widget, runAgainst, dataset))
                    .as("%s produced no rows -- a widget that draws nothing", widget.title)
                    .isGreaterThan(0);
            }
        }
        return dashboardId;
    }
}
