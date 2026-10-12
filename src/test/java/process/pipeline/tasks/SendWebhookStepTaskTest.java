package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.WebhookSender;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-346: Send webhook hands integration-service a summary or the rows for one registered subscription -- never a URL --
 * once per run, attempt and step (a retried try sends nothing again), waits a little for the first try's outcome, and
 * fails only when asked to require delivery; too many rows fail, never cut.
 */
class SendWebhookStepTaskTest {

    private final Fakes.Webhooks webhooks = new Fakes.Webhooks();
    private final SendWebhookStepTask task = new SendWebhookStepTask(this.webhooks);

    private TaskContext context(Object... settings) {
        return TaskContext.of(config(settings), Arrays.asList(row("patient", "P1", "flagged", 3L, "result", "high"),
            row("patient", "P2", "flagged", 3L, "result", "normal")));
    }

    @Test
    void aSummaryGoesToTheNamedSubscriptionWithFieldsFromTheFirstRow() throws Exception {
        TaskContext context = this.context("webhookId", 41, "fields", config("Flagged", "{{flagged}} of {{rows}}", "Run", "{{run}}"));
        StepResult result = this.task.run(context);
        assertThat(result.getOutput()).as("the rows pass on").isNull();
        assertThat(result.getRecordsOut()).isEqualTo(2L);
        WebhookSender.Send send = this.webhooks.sent.get(0);
        assertThat(send.webhookId).isEqualTo(41L);
        assertThat(send.tenantId).isEqualTo(TaskContext.TENANT);
        assertThat(send.subject).isEqualTo("run:" + TaskContext.RUN);
        assertThat(send.eventId).isEqualTo(SendWebhookStepTask.eventIdOf(TaskContext.TENANT, TaskContext.RUN, 1, context.stepKey()))
            .startsWith("step-").hasSizeLessThanOrEqualTo(64);
        assertThat(send.data).containsEntry("runId", String.valueOf(TaskContext.RUN)).containsEntry("mode", "summary")
            .containsEntry("rowCount", 2L).containsEntry("columns", Arrays.asList("patient", "flagged", "result"))
            .containsEntry("fields", config("Flagged", "3 of 2", "Run", String.valueOf(TaskContext.RUN))).doesNotContainKey("rows");
        assertThat(context.lines).anySatisfy(line -> assertThat(line).startsWith("INFO Queued for webhook 41: delivery 5000 (a summary of 2 row(s)"))
            .contains("INFO Delivered on try 1 (HTTP 200).");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRowsWhenAskedOnlyTheNamedColumnsAndARetriedTrySendsNothingAgain() throws Exception {
        this.task.run(this.context("webhookId", 41, "send", "rows", "columns", Arrays.asList("patient", "result")));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) this.webhooks.sent.get(0).data.get("rows");
        assertThat(rows).containsExactly(row("patient", "P1", "result", "high"), row("patient", "P2", "result", "normal"));
        TaskContext again = this.context("webhookId", 41, "send", "rows");
        this.task.run(again);
        assertThat(this.webhooks.sent).as("the same run, attempt and step: one delivery").hasSize(1);
        assertThat(again.lines.get(0)).startsWith("INFO Already queued for webhook 41: delivery 5000").contains("this try sends nothing again");
    }

    @Test
    void tooManyRowsFailAndAreNeverCut() {
        assertThatThrownBy(() -> this.task.run(this.context("webhookId", 41, "send", "rows", "maxRows", 1)))
            .hasMessage("The step's input has 2 rows; Send webhook sends at most 1 (At most rows). Put a limit or aggregate step before it, "
                + "or send a summary.");
        assertThat(this.webhooks.sent).isEmpty();
    }

    @Test
    void notDeliveredInTimeWarnsOrFailsWhenDeliveryIsRequired() throws Exception {
        this.webhooks.deliveryStatus = "Retrying";
        TaskContext lenient = this.context("webhookId", 41, "waitSeconds", 1);
        this.task.run(lenient);
        assertThat(lenient.lines).anySatisfy(line -> assertThat(line).startsWith("WARN Not delivered yet: Retrying after 1 try(s) -- HTTP 500."));
        this.webhooks.deliveries.clear();
        assertThatThrownBy(() -> this.task.run(this.context("webhookId", 41, "waitSeconds", 1, "requireDelivery", true)))
            .hasMessageStartingWith("Not delivered yet: Retrying after 1 try(s) -- HTTP 500. (Fail unless delivered is on;");
    }

    @Test
    void integrationServicesRefusalIsTheStepsError() {
        this.webhooks.refusal = "Webhook 41 does not subscribe to run.step.sent; add that event type to it in Integration › API Clients.";
        assertThatThrownBy(() -> this.task.run(this.context("webhookId", 41))).hasMessageContaining("does not subscribe to run.step.sent");
    }

    @Test
    void settingsAreChecked() {
        assertThat(this.task.check(config("webhookId", 1, "columns", Arrays.asList("a"))))
            .containsExactly(new DefinitionProblem("columns", "columns are for send: rows; a summary sends every column's name"));
        assertThat(this.task.check(config("webhookId", 1, "requireDelivery", true, "waitSeconds", 0)))
            .containsExactly(new DefinitionProblem("waitSeconds", "Fail unless delivered needs a wait of at least 1 second"));
        assertThat(this.task.spec().requiredRole()).isEqualTo("TENANT_ADMIN");
    }
}
