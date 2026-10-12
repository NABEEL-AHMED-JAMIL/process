package process.pipeline.tasks;

import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.PipelineNotifier;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Send Notification (MIG-231): a notification-centre notice about the run, through Core's own path to
 * notifications-service (the outbox) -- to the job's owner (the default), the workspace's admins, everyone in it, or
 * people named by id (members only). {@code when}: always, only when the input has rows, or only when it has none.
 * Title and message take the run's placeholders and {{rows}}, and the first row's columns as {{column}} (a one-row
 * summary -- an aggregate, an AI-written digest -- is told in the notice itself). The rows pass on unchanged.
 */
@Component
public class SendNotificationStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("send_notification", "Send Notification", TaskKind.OUTPUT)
        .description("Sends a notice about the run to the job's owner, the workspace's admins, everyone, or named people.")
        .input(TaskSpec.rows("Any rows; {{rows}} is how many."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(JsonSchema.object()
            .required("title", JsonSchema.string().minLength(1).maxLength(200).title("Title").format("template")
                .description("{{rows}}, {{pipeline}}, {{run}}, {{date}} and the other run placeholders are filled in; so is"
                    + " {{column}}, from the first row."))
            .property("message", JsonSchema.string().maxLength(2000).title("Message").format("multiline"))
            .property("severity", JsonSchema.string().enumOf("INFO", "SUCCESS", "WARNING", "ERROR").title("Severity").defaultValue("INFO"))
            .property("to", JsonSchema.string().enumOf("owner", "admins", "everyone", "users").title("To").defaultValue("owner"))
            .property("userIds", JsonSchema.array(JsonSchema.integer().minimum(1)).maxItems(PipelineNotifier.MAX_RECIPIENTS)
                .title("People").format("user").description("to: users — members of the workspace, by id."))
            .property("when", JsonSchema.string().enumOf("always", "has_rows", "no_rows").title("Send").defaultValue("always"))
            .property("link", JsonSchema.string().pattern("^/[^\\s]{0,254}$").title("Link").defaultValue("/jobList")
                .description("A console path the notice opens, starting with /")))
        .backing(TaskSpec.NOTIFICATIONS)
        .timeoutSeconds(120)
        .aiToolName("send_notification")
        .build();

    private final PipelineNotifier notifier;

    public SendNotificationStepTask(PipelineNotifier notifier) {
        super(SPEC);
        this.notifier = notifier;
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        Object ids = config.get("userIds");
        if ("users".equals(config.get("to")) && !(ids instanceof List && !((List<?>) ids).isEmpty())) {
            return Collections.singletonList(new DefinitionProblem("userIds", "name at least one person"));
        }
        return Collections.emptyList();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        // MIG-344: the input's size and first row only -- it may be a streamed table far bigger than a step can hold.
        int rows = (int) Math.min(Integer.MAX_VALUE, context.inputSize());
        String when = Configs.text(config, "when", "always");
        if (("has_rows".equals(when) && rows == 0) || ("no_rows".equals(when) && rows > 0)) {
            context.log(String.format("Not sent: %d row(s) and when is %s.", rows, when));
            return StepResult.nothing((long) rows);
        }
        // The first row's columns, under the run's own placeholders: {{run}} stays the run whatever a column is called.
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = rows > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, rows));
        PipelineNotifier.Notice notice = new PipelineNotifier.Notice();
        notice.tenantId = context.tenantId();
        notice.to = Configs.text(config, "to", "owner");
        notice.ownerUserId = context.jobOwnerUserId();
        notice.userIds = new ArrayList<>();
        Object ids = config.get("userIds");
        if (ids instanceof List) {
            for (Object id : (List<?>) ids) {
                if (id instanceof Number) {
                    notice.userIds.add(((Number) id).longValue());
                }
            }
        }
        notice.severity = Configs.text(config, "severity", "INFO");
        notice.title = Templates.fill(Configs.text(config, "title", ""), values);
        notice.body = Templates.fill(Configs.text(config, "message", null), values);
        notice.link = Configs.text(config, "link", "/jobList");
        int sent = this.notifier.send(notice);
        if (sent == 0) {
            context.warn(String.format("Nobody to notify (%s).", notice.to));
        } else {
            context.log(String.format("Sent to %d %s.", sent, sent == 1 ? "person" : "people"));
        }
        return StepResult.nothing((long) rows);
    }
}
