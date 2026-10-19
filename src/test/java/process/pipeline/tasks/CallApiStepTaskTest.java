package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.ApiRunner;
import process.pipeline.data.Values;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
        final List<ApiCall> calls = new ArrayList<>();
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
}
