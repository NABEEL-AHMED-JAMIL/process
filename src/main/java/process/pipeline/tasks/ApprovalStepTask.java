package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineDefinition;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Approval (MIG-346): pauses the run here until people approve it -- anywhere in a pipeline, e.g. before a webhook sends
 * results out. The run shows "Waiting for approval" and its reviewers get a Task inbox task (the same machinery as a run's
 * internal review, MIG-361: Core's event to workflow-service, whose decision comes back to Core). Approved, the run
 * continues from the next step on the rows this step read (it passes them on unchanged); rejected, the run fails with the
 * reviewer's reason. Nobody deciding within {@code expiresAfterHours} fails it too. A run that failed here, or after it, is
 * retried from its failed step as any run: a retry from this step asks again, one from a later step reuses the approval.
 *
 * Reviewers: the step's own (a role, or a group by its access profile id), else the pipeline's review reviewers, else the
 * workspace's administrators. The task's title, instructions and fields fill from the run and the first row
 * ({{column}}), so a reviewer sees what they approve: "Send {{rows}} lab results ({{abnormal_count}} abnormal)?".
 */
@Component
public class ApprovalStepTask extends RegisteredTask {

    static final int DEFAULT_HOURS = 72;
    static final int MAX_HOURS = 720;

    static final TaskSpec SPEC = TaskSpec.builder("approval", "Approval", TaskKind.PROCESS)
        .description("Pauses the run until its reviewers approve it in the Task inbox; rejected (or not decided in time), the run fails.")
        .input(TaskSpec.rows("The rows to approve; shown to the reviewers by count and fields."))
        .output(TaskSpec.rows("The same rows, unchanged, once approved."))
        .config(JsonSchema.object()
            .property("title", JsonSchema.string().maxLength(200).format("template").title("Task title")
                .description("What the reviewers' task says; fills from the run and the first row: Approve {{rows}} results of run {{run}}."))
            .property("instructions", JsonSchema.string().maxLength(2000).format("multiline").title("What to check")
                .description("Shown on the task; fills from the run and the first row."))
            .property("fields", JsonSchema.map(JsonSchema.string().maxLength(500).format("template")).title("Fields")
                .description("Name and value pairs shown on the task: Abnormal = {{abnormal_count}}."))
            .property("reviewers", JsonSchema.object()
                .property("kind", JsonSchema.string().enumOf("role", "group").title("Reviewers are"))
                .property("value", JsonSchema.string().maxLength(64).title("Role or group")
                    .description("For a role: " + PipelineDefinition.Reviewers.ADMINS + " (the tenant administrators) or TENANT_USER "
                        + "(every tenant user); for a group: an access profile's id."))
                .title("Reviewers").description("Empty: the pipeline's review reviewers, else the workspace's administrators."))
            .property("expiresAfterHours", JsonSchema.integer().minimum(1).maximum(MAX_HOURS).title("Decide within (hours)")
                .defaultValue(DEFAULT_HOURS).description("Nobody deciding in this time fails the run.")))
        .backing(TaskSpec.CORE)
        .aiToolName("request_approval")
        .build();

    public ApprovalStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Object reviewers = config.get("reviewers");
        if (reviewers instanceof Map && !((Map<?, ?>) reviewers).isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> who = (Map<String, Object>) reviewers;
            String kind = Configs.text(who, "kind", null);
            String value = Configs.text(who, "value", null);
            if (kind == null) {
                problems.add(new DefinitionProblem("reviewers.kind", "role or group"));
            } else if (PipelineDefinition.Reviewers.ROLE.equals(kind) && !PipelineDefinition.Reviewers.ROLES.contains(value)) {
                problems.add(new DefinitionProblem("reviewers.value", "a role: one of " + PipelineDefinition.Reviewers.ROLES));
            } else if (PipelineDefinition.Reviewers.GROUP.equals(kind) && (value == null || !value.matches("\\d{1,18}"))) {
                problems.add(new DefinitionProblem("reviewers.value", "a group: the access profile's id"));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        long size = context.inputSize();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = size > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, size)));
        StepResult.ApprovalAsk ask = new StepResult.ApprovalAsk();
        ask.title = Templates.fill(Configs.text(config, "title", "Approve run {{run}} to continue ({{rows}} rows at step {{step}})"), values);
        if (ask.title.length() > 255) {
            ask.title = ask.title.substring(0, 255);
        }
        ask.instructions = Templates.fill(Configs.text(config, "instructions", null), values);
        Configs.textMap(config, "fields").forEach((name, template) -> ask.fields.put(name, Templates.fill(template, values)));
        Object reviewers = config.get("reviewers");
        if (reviewers instanceof Map && !((Map<?, ?>) reviewers).isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> who = (Map<String, Object>) reviewers;
            ask.reviewersKind = Configs.text(who, "kind", null);
            ask.reviewersValue = Configs.text(who, "value", null);
        }
        ask.expiresAfterHours = Configs.integer(config, "expiresAfterHours", DEFAULT_HOURS);
        context.log(String.format("Asking for approval: %s.", ask.title));
        return StepResult.awaiting(ask, size);
    }
}
