package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.WebhookSender;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Send webhook (MIG-346): posts the step's rows, or a summary of them, to one of the workspace's registered webhooks --
 * anywhere in a pipeline, not only when the run ends. integration-service delivers it as every webhook: signed with the
 * subscription's secret, retried with backoff for a day, through its outbound guard (no private addresses), and logged in
 * the webhook's deliveries. Never to a URL the step names: only to a subscription of this workspace (Developers › API
 * Clients › Webhooks) that is active and takes the {@value WebhookSender#EVENT_TYPE} event type.
 *
 * <ul>
 *   <li>{@code send: summary} (the default): the row count, the columns, and header fields filled from the run and the
 *       first row ({{column}}) -- e.g. "Flagged = {{flagged_count}}".</li>
 *   <li>{@code send: rows}: the rows themselves (the named columns, or all), at most {@code maxRows}; more fails the step
 *       and says to put a limit or aggregate before it, never cut silently.</li>
 * </ul>
 * The delivery's id is the run's step and attempt: a retried try sends nothing twice (integration-service answers the first
 * delivery), a new attempt of the run sends again. The step waits up to {@code waitSeconds} for the first try's outcome
 * and logs it; {@code requireDelivery} fails the step when it was not delivered by then. The rows pass on unchanged.
 */
@Component
public class SendWebhookStepTask extends RegisteredTask implements StreamingStepTask {

    static final int DEFAULT_MAX_ROWS = 500;
    static final int MAX_ROWS = 5000;
    static final int MAX_WAIT_SECONDS = 60;

    static final TaskSpec SPEC = TaskSpec.builder("send_webhook", "Send webhook", TaskKind.OUTPUT)
        .description("Posts the rows, or a summary of them, to one of the workspace's registered webhooks (signed, retried, logged).")
        .input(TaskSpec.rows("The rows to send or summarise."))
        .output(TaskSpec.rows("The same rows, unchanged."))
        .config(JsonSchema.object()
            .required("webhookId", JsonSchema.integer().minimum(1).title("Webhook").format("webhook")
                .description("One of this workspace's webhooks (Developers › API Clients › Webhooks), by its id; it must be active and "
                    + "take the run.step.sent event type."))
            .property("send", JsonSchema.string().enumOf("summary", "rows").title("Send").defaultValue("summary")
                .description("summary: the row count, the columns and the fields below; rows: the rows themselves (at most At most rows)."))
            .property("columns", JsonSchema.array(JsonSchema.string().format("column")).maxItems(200).title("Columns")
                .description("For rows: which columns, in order; all of them when empty."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("At most rows").defaultValue(DEFAULT_MAX_ROWS)
                .description("For rows: more rows than this fails the step (put a limit or aggregate before it); nothing is cut."))
            .property("fields", JsonSchema.map(JsonSchema.string().maxLength(500).format("template")).title("Fields")
                .description("Name and value pairs sent with it, filled from the run and the first row: Flagged = {{flagged}}."))
            .property("waitSeconds", JsonSchema.integer().minimum(0).maximum(MAX_WAIT_SECONDS).title("Wait for the first try (s)")
                .defaultValue(10).description("How long the step waits to log whether the first try was delivered; it is retried "
                    + "in the background either way."))
            .property("requireDelivery", JsonSchema.bool().title("Fail unless delivered").defaultValue(false)
                .description("Fail the step when the webhook has not taken it within the wait.")))
        .backing(TaskSpec.INTEGRATION)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .timeoutSeconds(120)
        .aiToolName("send_webhook")
        .build();

    private final WebhookSender webhooks;

    public SendWebhookStepTask(WebhookSender webhooks) {
        super(SPEC);
        this.webhooks = webhooks;
    }

    @Override
    public Optional<String> unavailable() {
        return this.webhooks.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (!"rows".equals(Configs.text(config, "send", "summary")) && !Configs.texts(config, "columns").isEmpty()) {
            problems.add(new DefinitionProblem("columns", "columns are for send: rows; a summary sends every column's name"));
        }
        if (Configs.bool(config, "requireDelivery", false) && Configs.integer(config, "waitSeconds", 10) == 0) {
            problems.add(new DefinitionProblem("waitSeconds", "Fail unless delivered needs a wait of at least 1 second"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        long webhookId = Configs.longValue(config, "webhookId");
        boolean rowsMode = "rows".equals(Configs.text(config, "send", "summary"));
        long size = context.inputSize();
        Map<String, Object> first = size > 0 ? context.firstInputRow() : null;
        Map<String, Object> values = new LinkedHashMap<>();
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, size)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", String.valueOf(context.jobQueueId()));
        data.put("pipelineId", context.pipelineId());
        data.put("stepKey", context.stepKey());
        data.put("attempt", context.attempt());
        data.put("mode", rowsMode ? "rows" : "summary");
        data.put("rowCount", size);
        List<String> columns;
        if (rowsMode) {
            int maxRows = Configs.integer(config, "maxRows", DEFAULT_MAX_ROWS);
            if (size > maxRows) {
                throw new IllegalStateException(String.format("The step's input has %,d rows; Send webhook sends at most %,d (At most rows). "
                    + "Put a limit or aggregate step before it, or send a summary.", size, maxRows));
            }
            List<Map<String, Object>> rows = new ArrayList<>((int) size);
            try (RowSource input = context.openInput()) {
                columns = Configs.texts(config, "columns");
                if (columns.isEmpty()) {
                    columns = new ArrayList<>(input.columns());
                } else {
                    for (String column : columns) {
                        if (!input.columns().contains(column)) {
                            throw new IllegalArgumentException(String.format("The rows have no column '%s'.", column));
                        }
                    }
                }
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    for (Map<String, Object> row : batch) {
                        Map<String, Object> picked = new LinkedHashMap<>();
                        for (String column : columns) {
                            picked.put(column, row.get(column));
                        }
                        rows.add(picked);
                    }
                }
            }
            data.put("columns", columns);
            data.put("rows", rows);
        } else {
            columns = first == null ? new ArrayList<>() : new ArrayList<>(first.keySet());
            data.put("columns", columns);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Configs.textMap(config, "fields").forEach((name, template) -> fields.put(name, Templates.fill(template, values)));
        if (!fields.isEmpty() || !rowsMode) {
            data.put("fields", fields);
        }
        data.put("truncated", false);
        data.put("sentAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());

        WebhookSender.Send send = new WebhookSender.Send();
        send.tenantId = context.tenantId();
        send.jobQueueId = context.jobQueueId();
        send.stepKey = context.stepKey();
        send.attempt = context.attempt();
        send.webhookId = webhookId;
        send.eventId = eventIdOf(context.tenantId(), context.jobQueueId(), context.attempt(), context.stepKey());
        send.subject = "run:" + context.jobQueueId();
        send.data = data;
        WebhookSender.Sent sent = this.webhooks.send(send);
        context.log(String.format("%s webhook %d: delivery %d (%s, %s%s), event %s.", sent.replay ? "Already queued for" : "Queued for",
            webhookId, sent.deliveryId, rowsMode ? String.format("%,d row(s)", size) : String.format("a summary of %,d row(s)", size),
            String.format("%d field(s)", fields.size()), sent.replay ? "; this try sends nothing again" : "", sent.eventId));

        int wait = Configs.integer(config, "waitSeconds", 10);
        boolean required = Configs.bool(config, "requireDelivery", false);
        WebhookSender.Delivery delivery = null;
        long until = System.nanoTime() + wait * 1_000_000_000L;
        while (wait > 0) {
            delivery = this.webhooks.delivery(context.tenantId(), webhookId, sent.deliveryId);
            if (!"Pending".equals(delivery.status) || System.nanoTime() >= until) {
                break;
            }
            Thread.sleep(500);
        }
        if (delivery != null) {
            if ("Delivered".equals(delivery.status)) {
                context.log(String.format("Delivered on try %d (HTTP %s).", delivery.attempts, delivery.responseStatus));
            } else {
                String state = String.format("Not delivered yet: %s after %d try(s)%s.", delivery.status, delivery.attempts,
                    delivery.lastError == null ? "" : " -- " + delivery.lastError);
                if (required) {
                    throw new IllegalStateException(state + " (Fail unless delivered is on; the delivery is still retried and shows "
                        + "in the webhook's log.)");
                }
                context.warn(state + " integration-service keeps retrying it; see the webhook's deliveries.");
            }
        }
        return StepResult.nothing(size);
    }

    /** The delivery's event id: one per run, attempt and step, so a retried try is the same event. */
    static String eventIdOf(long tenantId, long jobQueueId, int attempt, String stepKey) {
        return "step-" + UUID.nameUUIDFromBytes(String.format("pipeline-step:%d:%d:%d:%s", tenantId, jobQueueId, attempt, stepKey)
            .getBytes(StandardCharsets.UTF_8));
    }
}
