package process.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ResponseDto;
import process.pipeline.backing.ApiRunner;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.registry.TaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.tasks.AggregateStepTask;
import process.pipeline.tasks.EnrichStepTask;
import process.pipeline.tasks.FilterStepTask;
import process.pipeline.tasks.JoinStepTask;
import process.pipeline.tasks.SaveFileStepTask;
import process.pipeline.tasks.TransformStepTask;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI tools batch (owner item #14): POST pipeline.json/steps/preview. A rows-to-rows task runs on the rows given and answers
 * the output's columns and at most 20 masked rows; nothing that writes, sends or saves can be previewed; the caps hold;
 * a task switched off for the workspace is refused; enrich reads only its own workspace's read-only requests, at most 10
 * calls; nothing is kept (no file, no output).
 */
@SuppressWarnings("unchecked")
class StepPreviewTest {

    static final long TENANT = 2960L;
    static final long OTHER = 2961L;

    private final Map<String, Boolean> switches = new HashMap<>();
    private final AtomicInteger apiCalls = new AtomicInteger();
    /** requestId -> {tenant, method} as integration-service knows them. */
    private final Map<Long, Object[]> requests = new HashMap<>();

    private StepPreview preview;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 4640L, "admin@riverside.test");
        StepTasks tasks = Definitions.builtInTasks(new TransformStepTask(), new FilterStepTask(), new AggregateStepTask(), new JoinStepTask(),
            new SaveFileStepTask(), new EnrichStepTask(this.runner()));
        TaskOverrideStore store = new TaskOverrideStore() {
            @Override
            public Map<String, Boolean> overrides(long tenantId) {
                return tenantId == TENANT ? StepPreviewTest.this.switches : Collections.emptyMap();
            }

            @Override
            public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
            }

            @Override
            public boolean clear(long tenantId, String taskCode) {
                return false;
            }
        };
        this.preview = new StepPreview(new TaskRegistry(tasks, store));
        this.preview.useIntegration(this.runner(), this.names());
        this.requests.put(77L, new Object[] {TENANT, "GET"});
        this.requests.put(78L, new Object[] {TENANT, "POST"});
        this.requests.put(99L, new Object[] {OTHER, "GET"});
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ApiRunner runner() {
        return new ApiRunner() {
            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }

            @Override
            public ApiRunResult run(ApiCall call) {
                StepPreviewTest.this.apiCalls.incrementAndGet();
                ApiRunResult r = new ApiRunResult();
                r.outcome = "OK";
                r.statusCode = 200;
                r.body = new ObjectMapper().createObjectNode().put("score", call.variables.getOrDefault("id", "?").length());
                return r;
            }
        };
    }

    private IntegrationNames names() {
        return (tenantId, requestIds, connectionIds) -> {
            IntegrationNames.Names n = new IntegrationNames.Names();
            for (Long id : requestIds) {
                Object[] r = this.requests.get(id);
                if (r != null && (long) r[0] == tenantId) {
                    IntegrationNames.RequestName name = new IntegrationNames.RequestName();
                    name.name = "request " + id;
                    name.method = (String) r[1];
                    n.requests.put(id, name);
                }
            }
            return n;
        };
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) row.put((String) kv[i], kv[i + 1]);
        return row;
    }

    private static StepPreview.PreviewRequest request(String task, Map<String, Object> config, List<Map<String, Object>> rows) {
        StepPreview.PreviewRequest r = new StepPreview.PreviewRequest();
        r.task = task;
        r.config = config;
        r.rows = rows;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseDto answer) {
        return (Map<String, Object>) answer.getData();
    }

    private static List<Map<String, Object>> patients(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row("id", "p" + i, "patient_name", "Jane Doe " + i, "status", i % 2 == 0 ? "active" : "closed", "age", 30 + i));
        }
        return rows;
    }

    @Test
    void aTransformRunsOnTheRowsGivenAndAnswersColumnsAndMaskedRows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mappings", Arrays.asList(row("target", "STATUS", "op", "upper", "source", "status"), row("target", "who", "op", "copy", "source",
            "patient_name")));
        config.put("keepUnmapped", true);
        ResponseDto answer = this.preview.preview(request("transform", config, patients(30)));
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        Map<String, Object> data = data(answer);
        assertThat((List<String>) data.get("columns")).contains("STATUS", "who", "patient_name");
        assertThat(data.get("rowCount")).isEqualTo(30);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        assertThat(rows).hasSize(StepPreview.SHOWN);
        assertThat(rows.get(0).get("STATUS")).isEqualTo("ACTIVE");
        // A person's name is shown by its form only.
        assertThat(rows.get(0).get("patient_name")).isEqualTo("Xxxx Xxx 9");
        assertThat((List<String>) data.get("masked")).contains("patient_name");
    }

    /**
     * ZT-M21: the preview masks a person's details by the column's name and the value's shape, never by counting values, so one
     * patient's row (or two) is masked as thirty are. Characterisation: it held before the fix; it must keep holding.
     */
    @Test
    void oneOrTwoPatientsRowsAreMaskedAsManyAre() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mappings", Collections.singletonList(row("target", "STATUS", "op", "upper", "source", "status")));
        config.put("keepUnmapped", true);
        for (int n = 1; n <= 2; n++) {
            List<Map<String, Object>> given = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                given.add(row("patient_name", i == 0 ? "Pat Synthetic" : "Robin Testcase", "email", "pat" + i + "@example.test", "ssn", "123-45-678" + i,
                    "status", "active"));
            }
            ResponseDto answer = this.preview.preview(request("transform", config, given));
            assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
            Map<String, Object> data = data(answer);
            String shown = String.valueOf(data.get("rows"));
            assertThat(shown).as(n + " row(s)").doesNotContain("Pat Synthetic").doesNotContain("Robin Testcase").doesNotContain("@example.test")
                .doesNotContain("123-45-678").contains("ACTIVE");
            assertThat((List<String>) data.get("masked")).contains("patient_name", "email", "ssn");
        }
    }

    @Test
    void aJoinReadsTheOtherSideFromItsInputs() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("with", "right");
        config.put("on", Collections.singletonList(row("left", "id", "right", "pid")));
        StepPreview.PreviewRequest r = request("join", config, patients(3));
        r.inputs = new LinkedHashMap<>();
        r.inputs.put("right", Arrays.asList(row("pid", "p0", "score", 9), row("pid", "p2", "score", 4)));
        ResponseDto answer = this.preview.preview(r);
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(data(answer).get("rowCount")).isEqualTo(2);
        assertThat((List<String>) data(answer).get("columns")).contains("score");
    }

    @Test
    void nothingThatWritesSendsOrSavesCanBePreviewed() {
        for (String task : Arrays.asList("save_file", "legacy", "send_webhook", "ai_prompt", "write_database", "upload_bucket")) {
            ResponseDto answer = this.preview.preview(request(task, new LinkedHashMap<>(), patients(2)));
            assertThat(answer.getStatus()).as(task).isEqualTo(ProcessUtil.ERROR);
            assertThat(answer.getMessage()).as(task).containsAnyOf("cannot be previewed", "There is no task");
        }
    }

    @Test
    void aPreviewKeepsNothing() {
        StepPreview.PreviewContext context = new StepPreview.PreviewContext(TENANT, new HashMap<>(), Dataset.of(patients(1)), new HashMap<>());
        try {
            context.keepFile("out.csv", new byte[] {1}, 1, Collections.singletonList("a"));
            assertThat(false).as("keepFile must refuse").isTrue();
        } catch (UnsupportedOperationException expected) {
            assertThat(expected.getMessage()).contains("keeps no files");
        }
        assertThat(context.jobQueueId()).isZero();
    }

    @Test
    void theCapsHold() {
        ResponseDto tooMany = this.preview.preview(request("filter", row("conditions", Collections.singletonList(row("column", "age", "operator",
            "gt", "value", 1))), patients(51)));
        assertThat(tooMany.getMessage()).contains("at most 50 rows");
        StepPreview.PreviewRequest inputs = request("join", row("with", "a", "on", Collections.singletonList(row("left", "id", "right", "id"))), patients(1));
        inputs.inputs = new LinkedHashMap<>();
        for (String k : Arrays.asList("a", "b", "c", "d", "e")) inputs.inputs.put(k, patients(1));
        assertThat(this.preview.preview(inputs).getMessage()).contains("at most 4 other inputs");
        List<Map<String, Object>> wide = new ArrayList<>();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30_000; i++) big.append('x');
        for (int i = 0; i < 40; i++) wide.add(row("text", big.toString()));
        assertThat(this.preview.preview(request("filter", row("conditions", Collections.singletonList(row("column", "text", "operator", "is_not_null"))),
            wide)).getMessage()).contains("larger than a preview takes");
    }

    @Test
    void aTaskSwitchedOffForTheWorkspaceIsRefused() {
        this.switches.put("transform", false);
        ResponseDto answer = this.preview.preview(request("transform", row("mappings", Collections.singletonList(row("target", "a", "source", "id"))),
            patients(2)));
        assertThat(answer.getStatus()).isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void invalidSettingsAreSaidAtTheirPath() {
        ResponseDto answer = this.preview.preview(request("aggregate", row("aggregations", "not a list"), patients(2)));
        assertThat(answer.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(answer.getMessage()).contains("aggregations");
    }

    @Test
    void enrichReadsOnlyTheWorkspacesOwnReadOnlyRequestsAtMostTenCalls() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("requestId", 77);
        config.put("variables", row("id", "{{id}}"));
        config.put("fields", Collections.singletonList(row("path", "score", "target", "score")));
        config.put("maxCalls", 500);
        ResponseDto ok = this.preview.preview(request("enrich", config, patients(8)));
        assertThat(ok.getStatus()).as(ok.getMessage()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(this.apiCalls.get()).isEqualTo(8);

        // More distinct calls than the preview's cap: refused before any call (maxCalls was held to 10).
        this.apiCalls.set(0);
        ResponseDto many = this.preview.preview(request("enrich", config, patients(12)));
        assertThat(many.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(this.apiCalls.get()).isZero();

        config.put("requestId", 78);
        assertThat(this.preview.preview(request("enrich", config, patients(2))).getMessage()).contains("changes data (POST)");
        config.put("requestId", 99);
        assertThat(this.preview.preview(request("enrich", config, patients(2))).getMessage())
            .isEqualTo("There is no API request 99 in this workspace.");
        assertThat(this.apiCalls.get()).isZero();
    }

    @Test
    void onlyAnAdministratorPreviewsAStepThatCallsAnApi() {
        TenantContext.set(TENANT, "TENANT_USER", 4642L, "viewer@riverside.test");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("requestId", 77);
        config.put("fields", Collections.singletonList(row("path", "score", "target", "score")));
        assertThat(this.preview.preview(request("enrich", config, patients(1))).getMessage()).contains("a workspace administrator's");
        assertThat(this.apiCalls.get()).isZero();
        // A step that calls nothing is anyone's to preview.
        assertThat(this.preview.preview(request("filter", row("conditions", Collections.singletonList(row("column", "age", "operator", "gt",
            "value", 40))), patients(2))).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void anotherWorkspacesRequestIsNotFound() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("requestId", 99);
        config.put("fields", Collections.singletonList(row("path", "score", "target", "score")));
        ResponseDto answer = this.preview.preview(request("enrich", config, patients(1)));
        assertThat(answer.getMessage()).isEqualTo("There is no API request 99 in this workspace.");
        assertThat(this.apiCalls.get()).isZero();
    }

    @Test
    void noWorkspaceNoPreview() {
        TenantContext.set(null, "PLATFORM_ADMIN", 4639L, "qa-platform-admin@platform.local");
        assertThat(this.preview.preview(request("filter", new HashMap<>(), patients(1))).getMessage()).contains("runs in a workspace");
    }

    @Test
    void maskingKeepsTheFormOfPersonalValues() {
        Set<String> masked = new LinkedHashSet<>();
        Map<String, Object> out = StepPreview.mask(row("email", "jane@x.org", "note", "call 512-555-0199", "phone_note", null, "score", 4,
            "contact", "jane.doe@example.com"), masked);
        assertThat(out.get("email")).isEqualTo("xxxx@x.xxx");
        assertThat(out.get("contact")).isEqualTo("xxxx.xxx@xxxxxxx.xxx");
        assertThat(out.get("score")).isEqualTo(4);
        assertThat(masked).contains("email", "contact").doesNotContain("score", "phone_note");
    }

    @SuppressWarnings("unused")
    private static Collection<Long> none() {
        return Collections.emptyList();
    }
}
