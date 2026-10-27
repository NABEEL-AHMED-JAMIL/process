package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.StepContext.RunFile;
import process.pipeline.backing.ApiRunner;
import process.pipeline.data.Values;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * Owner #25 (2026-10-19): Call API sends a saved request once or per row and keeps each answer's status, ok, error and
 * fields on the rows, so later steps branch on them; a blocked call is never a branch; the same call key on every try.
 */
class CallApiStepTaskTest {

    /** Answers each call from its variables: [status, body]. */
    static final class Target implements ApiRunner {
        final List<ApiCall> calls = Collections.synchronizedList(new ArrayList<>());
        Function<Map<String, String>, Object[]> answer = v -> new Object[] {201, "{\"id\":\"c-1\",\"result\":{\"status\":\"accepted\"}}"};
        String outcome;

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public ApiRunResult run(ApiCall call) throws Exception {
            this.calls.add(call);
            Object[] answered = this.answer.apply(call.variables);
            ApiRunResult result = new ApiRunResult();
            result.statusCode = (Integer) answered[0];
            result.body = answered[1] == null ? null : Values.JSON.readTree((String) answered[1]);
            result.outcome = this.outcome != null ? this.outcome
                : result.statusCode != null && result.statusCode < 300 ? "OK" : "FAILED";
            result.message = "OK".equals(result.outcome) ? null : this.outcome != null ? "the address is not allowed" : "Unprocessable";
            return result;
        }
    }

    private final Target api = new Target();
    private final CallApiStepTask task = new CallApiStepTask(this.api);

    private static List<Map<String, Object>> referrals() {
        return Arrays.asList(row("patient_id", "P-1", "clinic", "north"), row("patient_id", "P-2", "clinic", "south"));
    }

    private TaskContext context(Object... more) {
        Object[] base = {"requestId", 12, "variables", config("patient", "{{patient_id}}", "key", "{{call_key}}"), "fields",
            Collections.singletonList(config("path", "id", "target", "referral_id"))};
        Object[] settings = new Object[base.length + more.length];
        System.arraycopy(base, 0, settings, 0, base.length);
        System.arraycopy(more, 0, settings, base.length, more.length);
        return TaskContext.of(config(settings), referrals());
    }

    @Test
    void eachRowIsSentAndKeepsItsStatusOkAndFields() throws Exception {
        Dataset out = this.task.run(this.context()).getOutput();
        assertThat(this.api.calls).hasSize(2);
        assertThat(this.api.calls.get(0).variables).containsEntry("patient", "P-1");
        assertThat(this.api.calls.get(0).requestId).isEqualTo(12L);
        assertThat(out.getColumns()).containsExactly("patient_id", "clinic", "api_status", "api_ok", "api_error", "referral_id");
        assertThat(out.getRows()).allMatch(r -> Integer.valueOf(201).equals(r.get("api_status")) && Boolean.TRUE.equals(r.get("api_ok"))
            && r.get("api_error") == null && "c-1".equals(r.get("referral_id")));
    }

    /**
     * ZT-S5 (showcase walk-in, 2026-10-26): a Call API with no rows to send -- an earlier read found nothing, so the rows have
     * only the other side's columns -- made no call but failed on "Columns sent names mrn, which the rows have not". With
     * nothing to send there is nothing to check: no call, no rows, the step done; a named column is still checked on rows.
     */
    @Test
    void noRowsToSendIsNoCallNotAColumnProblem() throws Exception {
        TaskContext context = new TaskContext(config("requestId", 12, "variables", config("payload", "{{row}}"),
            "columns", Collections.singletonList(config("column", "mrn", "as", "patient.mrn"))),
            new Dataset(Arrays.asList("wanted", "plan_code"), new ArrayList<>()));

        Dataset out = this.task.run(context).getOutput();

        assertThat(this.api.calls).isEmpty();
        assertThat(out.getRows()).isEmpty();
        assertThatThrownBy(() -> this.task.run(new TaskContext(context.config, Dataset.of(Collections.singletonList(row("wanted", true))))))
            .hasMessageContaining("Columns sent names mrn, which the rows have not");
    }

    @Test
    void anAnswerThatIsNotOkFailsTheStepByDefault() {
        this.api.answer = v -> "P-2".equals(v.get("patient")) ? new Object[] {422, "{\"error\":\"duplicate\"}"} : new Object[] {201, "{}"};
        assertThatThrownBy(() -> this.task.run(this.context())).hasMessageContaining("Call 2 of 2 was not ok: HTTP 422")
            .hasMessageContaining("set it to continue");
    }

    @Test
    void continueKeepsEveryRowWithItsOutcomeForTheBranches() throws Exception {
        this.api.answer = v -> "P-2".equals(v.get("patient")) ? new Object[] {422, "{\"error\":\"duplicate\"}"} : new Object[] {201, "{\"id\":\"c-9\"}"};
        TaskContext context = this.context("onError", "continue");
        Dataset out = this.task.run(context).getOutput();
        assertThat(out.getRows().get(0)).containsEntry("api_status", 201).containsEntry("api_ok", true).containsEntry("referral_id", "c-9");
        assertThat(out.getRows().get(1)).containsEntry("api_status", 422).containsEntry("api_ok", false)
            .containsEntry("api_error", "HTTP 422: Unprocessable");
        // The branches are the pipeline's own: a Filter on api_ok reading this step takes each side.
        assertThat(FilterStepTask.meets(out.getRows().get(1).get("api_ok"), config("operator", "eq", "value", false))).isTrue();
        assertThat(context.lines).anyMatch(l -> l.contains("HTTP 201 x1, 422 x1"));
    }

    @Test
    void okWhenJudgesTheBodyBeyondA2xx() throws Exception {
        this.api.answer = v -> "P-2".equals(v.get("patient")) ? new Object[] {200, "{\"result\":{\"status\":\"rejected\"}}"}
            : new Object[] {200, "{\"result\":{\"status\":\"accepted\"}}"};
        Dataset out = this.task.run(this.context("onError", "continue", "okWhen",
            Collections.singletonList(config("path", "result.status", "operator", "eq", "value", "accepted")))).getOutput();
        assertThat(out.getRows().get(0)).containsEntry("api_ok", true);
        assertThat(out.getRows().get(1)).containsEntry("api_ok", false)
            .containsEntry("api_error", "the answer's result.status is rejected, not eq accepted");
    }

    @Test
    void onceSendsOneCallFromTheFirstRowAndTheRun() throws Exception {
        Dataset out = this.task.run(this.context("mode", "once", "variables", config("count", "{{rows}}", "first", "{{patient_id}}"))).getOutput();
        assertThat(this.api.calls).hasSize(1);
        assertThat(this.api.calls.get(0).variables).containsEntry("count", "1").containsEntry("first", "P-1");
        assertThat(out.size()).isEqualTo(1);
    }

    @Test
    void aBlockedCallFailsTheStepWhateverOnErrorSays() {
        this.api.outcome = "BLOCKED";
        this.api.answer = v -> new Object[] {null, null};
        assertThatThrownBy(() -> this.task.run(this.context("onError", "continue"))).hasMessageContaining("blocked");
    }

    @Test
    void moreRowsThanMaxCallsFailsBeforeAnyCall() {
        assertThatThrownBy(() -> this.task.run(this.context("maxCalls", 1))).hasMessageContaining("at most 1 calls");
        assertThat(this.api.calls).isEmpty();
    }

    @Test
    void theCallKeyIsTheSameOnEveryTryOfARunAndDiffersByRow() throws Exception {
        this.task.run(this.context());
        this.task.run(this.context());
        assertThat(this.api.calls.get(0).variables.get("key")).isEqualTo(this.api.calls.get(2).variables.get("key"));
        assertThat(this.api.calls.get(0).variables.get("key")).isNotEqualTo(this.api.calls.get(1).variables.get("key"));
    }

    @Test
    void checkRefusesAnOkWhenWithoutItsValueAndATargetNamedLikeTheAnswersColumns() {
        assertThat(this.task.check(config("requestId", 1, "okWhen", Collections.singletonList(config("path", "a", "operator", "eq")),
            "fields", Collections.singletonList(config("path", "id", "target", "api_ok")))))
            .extracting(p -> p.getPath()).containsExactly("okWhen[0].value", "fields[0].target");
        assertThat(this.task.check(config("requestId", 1, "okWhen", Collections.singletonList(config("path", "a", "operator", "not_null"))))).isEmpty();
    }

    @Test
    void itIsAnAdministratorsOutputStepWithNoRetryOfItsOwn() {
        assertThat(CallApiStepTask.SPEC.code()).isEqualTo("call_api");
        assertThat(CallApiStepTask.SPEC.requiredRole()).isEqualTo("TENANT_ADMIN");
        assertThat(CallApiStepTask.SPEC.backingService()).isEqualTo("integration-service");
    }

    // ---- MIG-419: a row as one object, rows in batches, a file from an earlier step ----------------------------------

    private static List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(row("patient_id", "P-" + i, "clinic", i % 2 == 0 ? "north" : "south", "visits", i));
        }
        return out;
    }

    @Test
    void eachRowIsSentAsOneObjectWithChosenColumnsRenamedAndNested() throws Exception {
        TaskContext context = TaskContext.of(config("requestId", 12, "variables", config("payload", "{{row}}", "key", "{{call_key}}"),
            "columns", Arrays.asList(config("column", "patient_id", "as", "patient.id"), config("column", "clinic", "as", "patient.clinic"),
                config("column", "visits"))), rows(2));
        this.task.run(context);
        assertThat(this.api.calls).hasSize(2);
        assertThat(Values.JSON.readTree(this.api.calls.get(1).variables.get("payload")))
            .isEqualTo(Values.JSON.readTree("{\"patient\":{\"id\":\"P-1\",\"clinic\":\"south\"},\"visits\":1}"));
        assertThat(this.api.calls.get(0).jsonVariables).containsExactly("payload");
    }

    @Test
    void aColumnNamedRowStillFillsRowAsBefore() throws Exception {
        TaskContext context = TaskContext.of(config("requestId", 12, "variables", config("n", "{{row}}")),
            Collections.singletonList(row("row", "7", "x", 1)));
        this.task.run(context);
        assertThat(this.api.calls.get(0).variables).containsEntry("n", "7");
        assertThat(this.api.calls.get(0).jsonVariables).isNullOrEmpty();
    }

    @Test
    void rowsAreSentInBatchesAsOneArrayEachTheLastOneShorter() throws Exception {
        this.api.answer = v -> new Object[] {200, "{\"accepted\":true}"};
        TaskContext context = TaskContext.of(config("requestId", 12, "mode", "batch", "batchSize", 50,
            "variables", config("body", "{\"items\": {{rows_json}}}", "page", "{{batch}} of {{batches}}", "count", "{{batch_rows}}",
                "key", "{{call_key}}")), rows(120));
        Dataset out = this.task.run(context).getOutput();
        assertThat(this.api.calls).hasSize(3);
        assertThat(this.api.calls).extracting(c -> c.variables.get("page")).containsExactly("1 of 3", "2 of 3", "3 of 3");
        assertThat(this.api.calls).extracting(c -> c.variables.get("count")).containsExactly("50", "50", "20");
        assertThat(Values.JSON.readTree(this.api.calls.get(2).variables.get("body")).path("items").size()).isEqualTo(20);
        assertThat(Values.JSON.readTree(this.api.calls.get(2).variables.get("body")).path("items").get(0).path("patient_id").asText())
            .isEqualTo("P-100");
        assertThat(this.api.calls.get(0).jsonVariables).containsExactly("body");
        assertThat(out.size()).isEqualTo(120);
        assertThat(out.getRows()).allMatch(r -> Integer.valueOf(200).equals(r.get("api_status")) && Boolean.TRUE.equals(r.get("api_ok")));
        // The same call key per batch on every try of the run, one per batch.
        List<String> keys = new ArrayList<>();
        this.api.calls.forEach(c -> keys.add(c.variables.get("key")));
        assertThat(keys).doesNotHaveDuplicates();
        this.api.calls.clear();
        this.task.run(TaskContext.of(context.config, rows(120)));
        assertThat(this.api.calls).extracting(c -> c.variables.get("key")).containsExactlyElementsOf(keys);
    }

    @Test
    void aBatchsPerRowResultsAreMappedBackByPosition() throws Exception {
        this.api.answer = v -> {
            StringBuilder items = new StringBuilder();
            int n = Integer.parseInt(v.get("count"));
            for (int i = 0; i < n; i++) {
                items.append(i == 0 ? "" : ",").append("{\"ref\":\"r-").append(v.get("page")).append("-").append(i).append("\"}");
            }
            return new Object[] {200, "{\"results\":[" + items + "]}"};
        };
        Dataset out = this.task.run(TaskContext.of(config("requestId", 12, "mode", "batch", "batchSize", 2, "resultsPath", "results",
            "variables", config("body", "{{rows_json}}", "page", "{{batch}}", "count", "{{batch_rows}}"),
            "fields", Collections.singletonList(config("path", "ref", "target", "ref"))), rows(3))).getOutput();
        assertThat(out.getRows()).extracting(r -> r.get("ref")).containsExactly("r-1-0", "r-1-1", "r-2-0");
    }

    @Test
    void moreBatchesThanAtMostCallsAreRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 12, "mode", "batch", "batchSize", 10, "maxCalls", 2,
            "variables", config("body", "{{rows_json}}")), rows(25))))
            .hasMessageContaining("25 rows").hasMessageContaining("3 calls of 10 rows").hasMessageContaining("at most 2 calls");
        assertThat(this.api.calls).isEmpty();
    }

    @Test
    void aBatchTooBigToSendSaysItsSizeAndTheCap() {
        List<Map<String, Object>> big = new ArrayList<>();
        char[] text = new char[300_000];
        Arrays.fill(text, 'x');
        for (int i = 0; i < 40; i++) {
            big.add(row("id", i, "note", new String(text)));
        }
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 12, "mode", "batch", "batchSize", 40,
            "variables", config("body", "{{rows_json}}")), big)))
            .hasMessageContaining("Call 1 of 1").hasMessageContaining("MB").hasMessageContaining("at most 8 MB")
            .hasMessageContaining("Rows per call");
        assertThat(this.api.calls).isEmpty();
    }

    @Test
    void aFileAnEarlierStepUploadedIsNamedByItsBucketAndKey() throws Exception {
        TaskContext context = TaskContext.of(config("requestId", 12, "mode", "once", "file", config("step", "save"),
            "variables", config("bucket", "{{file_bucket}}", "key", "{{file_key}}", "name", "{{file_name}}")), rows(1));
        context.runFiles.put("save", Collections.singletonList(new RunFile("out.csv", "csv", 12L,
            "lakeside-s3", "results/out.csv", null)));
        this.task.run(context);
        assertThat(this.api.calls.get(0).variables).containsEntry("bucket", "lakeside-s3").containsEntry("key", "results/out.csv")
            .containsEntry("name", "out.csv");
    }

    @Test
    void aRequestWhoseBodyNamesTheFileGetsItWithoutTheStepRepeatingIt() throws Exception {
        TaskContext context = TaskContext.of(config("requestId", 12, "mode", "once", "file", config("step", "save"),
            "variables", config("key", "{{call_key}}", "file_name", "renamed.csv")), rows(1));
        context.runFiles.put("save", Collections.singletonList(new RunFile("out.csv", "csv", 12L,
            "lakeside-s3", "results/out.csv", null)));
        this.task.run(context);
        assertThat(this.api.calls.get(0).variables).containsEntry("file_bucket", "lakeside-s3").containsEntry("file_key", "results/out.csv")
            .containsEntry("file_format", "csv").containsEntry("file_name", "renamed.csv");
    }

    @Test
    void aStepWithoutAFileSendsNoFileValues() throws Exception {
        this.task.run(TaskContext.of(config("requestId", 12, "mode", "once", "variables", config("key", "{{call_key}}")), rows(1)));
        assertThat(this.api.calls.get(0).variables).doesNotContainKeys("file_bucket", "file_key", "file_name", "file_format");
    }

    @Test
    void aFileKeptWithTheRunIsPutInTheWorkspacesFileDropAndNamedThere() throws Exception {
        List<String> uploads = new ArrayList<>();
        CallApiStepTask staged = new CallApiStepTask(this.api);
        staged.useStorage((tenant, bucket, key, in, size, type) -> uploads.add(tenant + " " + bucket + " " + key + " " + size + " " + type
            + " " + new String(in.readAllBytes())), tenant -> "lakeside-drop");
        TaskContext context = TaskContext.of(config("requestId", 12, "mode", "once", "file", config("step", "report", "name", "report.pdf"),
            "variables", config("bucket", "{{file_bucket}}", "key", "{{file_key}}")), rows(1));
        context.runFiles.put("report", Arrays.asList(
            new RunFile("other.pdf", "pdf", 3L, null, null, () -> new ByteArrayInputStream("no".getBytes())),
            new RunFile("report.pdf", "pdf", 4L, null, null, () -> new ByteArrayInputStream("%PDF".getBytes()))));
        staged.run(context);
        assertThat(uploads).containsExactly(TaskContext.TENANT + " lakeside-drop intake/api/call-api/" + TaskContext.RUN + "/step/report.pdf 4 "
            + "application/pdf %PDF");
        assertThat(this.api.calls.get(0).variables).containsEntry("bucket", "lakeside-drop")
            .containsEntry("key", "intake/api/call-api/" + TaskContext.RUN + "/step/report.pdf");
    }

    @Test
    void aFileStepThatMadeNoFileSaysSo() {
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 12, "mode", "once", "file", config("step", "report"),
            "variables", config("key", "{{file_key}}")), rows(1)))).hasMessageContaining("step <report> made no file");
    }

    // ---- MIG-418: Calls at once -------------------------------------------------------------------------------------

    @Test
    void callsAtOnceKeepsTheRowsOrderAndBacksOffABusyAnswer() throws Exception {
        AtOnce.secondMillis = 1;
        try {
            AtomicInteger busy = new AtomicInteger();
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger most = new AtomicInteger();
            this.api.answer = v -> {
                most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep((Integer.parseInt(v.get("patient").substring(2)) * 7) % 11);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                if ("P-5".equals(v.get("patient")) && busy.incrementAndGet() == 1) {
                    return new Object[] {429, "{\"error\":\"slow down\"}"};
                }
                return new Object[] {201, "{\"id\":\"c-" + v.get("patient") + "\"}"};
            };
            List<Map<String, Object>> twenty = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                twenty.add(row("patient_id", "P-" + i));
            }
            Dataset out = this.task.run(TaskContext.of(config("requestId", 12, "callsAtOnce", 4, "variables", config("patient", "{{patient_id}}"),
                "fields", Collections.singletonList(config("path", "id", "target", "referral_id"))), twenty)).getOutput();
            assertThat(out.getRows()).extracting(r -> r.get("patient_id"))
                .containsExactlyElementsOf(twenty.stream().map(r -> r.get("patient_id")).collect(Collectors.toList()));
            assertThat(out.getRows()).allMatch(r -> ("c-" + r.get("patient_id")).equals(r.get("referral_id")) && Integer.valueOf(201).equals(r.get("api_status")));
            assertThat(this.api.calls).hasSize(21);
            assertThat(most.get()).isBetween(2, 4);
        } finally {
            AtOnce.secondMillis = 1000;
        }
    }

    @Test
    void callsAtOnceOneIsTheStepAsItWas() throws Exception {
        this.api.answer = v -> new Object[] {429, "{}"};
        Dataset out = this.task.run(this.context("onError", "continue")).getOutput();
        assertThat(this.api.calls).as("a busy answer is not asked again at 1: it is the row's outcome").hasSize(2);
        assertThat(out.getRows()).allMatch(r -> Integer.valueOf(429).equals(r.get("api_status")) && Boolean.FALSE.equals(r.get("api_ok")));
    }
}
