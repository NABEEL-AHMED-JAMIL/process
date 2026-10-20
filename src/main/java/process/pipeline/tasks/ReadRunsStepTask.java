package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.RunHistory;
import process.pipeline.data.Limits;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read runs (2026-10-19): a pipeline's runs in this workspace, one row each with its review -- status, the decision,
 * who decided, their comment or reason, when, and how long the run and the review took ({@link RunHistory#COLUMNS}). A
 * pipeline that reports on a review process reads its runs this way: join them to the results each run kept (by
 * {@code run_id}) and every result row carries the reviewer's decision, for a dashboard or a report.
 *
 * <p>{@code pipelineId} names the pipeline (its id, as XRAY_TRIAGE); empty, this run's own pipeline. Runs created in the
 * last {@code days} days (default 30), oldest first; runs still queued or running are left out unless
 * {@code includeRunning}. The workspace's own runs only: the engine reads as the run's workspace. A tenant administrator's
 * task (a run's review names its reviewer and their comment; a tenant user reads only their own runs elsewhere).
 */
@Component
public class ReadRunsStepTask extends RegisteredTask {

    public static final String CODE = "read_runs";
    static final int MAX_DAYS = 3660;

    static final TaskSpec SPEC = TaskSpec.builder(CODE, "Read runs", TaskKind.READ)
        .description("Reads a pipeline's runs in this workspace, one row each with its review: status, decision, reviewer, comment, "
            + "when, and how long the run and the review took.")
        .output(TaskSpec.rows("One row per run: " + String.join(", ", RunHistory.COLUMNS) + "."))
        .config(JsonSchema.object()
            .property("pipelineId", JsonSchema.string().maxLength(255).title("Pipeline")
                .description("The pipeline whose runs to read, by its id (XRAY_TRIAGE). Empty: this run's own pipeline."))
            .property("days", JsonSchema.integer().minimum(1).maximum(MAX_DAYS).title("Runs of the last (days)").defaultValue(30)
                .description("Runs created in this many days, up to now."))
            .property("includeRunning", JsonSchema.bool().title("Include runs still queued or running").defaultValue(false)
                .description("Off leaves them out (their review cannot have started)."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_ROWS).title("At most rows").defaultValue(10_000)
                .description("Read at most this many runs, the oldest first; the step warns when there were more.")))
        .backing(TaskSpec.CORE)
        // Runs name their reviewers and carry their comments: an administrator's read (a tenant user reads only their own runs).
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .aiToolName(CODE)
        .build();

    private final RunHistory history;

    public ReadRunsStepTask(RunHistory history) {
        super(SPEC);
        this.history = history;
    }

    @Override
    public Optional<String> unavailable() {
        return this.history.unavailable();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String pipelineId = Configs.text(config, "pipelineId", null);
        if (pipelineId == null || pipelineId.trim().isEmpty()) {
            pipelineId = context.pipelineId();
        }
        if (pipelineId == null || pipelineId.trim().isEmpty()) {
            throw new IllegalStateException("No pipeline to read the runs of: set pipelineId.");
        }
        pipelineId = pipelineId.trim();
        int days = Configs.integer(config, "days", 30);
        int maxRows = Math.min(Configs.integer(config, "maxRows", 10_000), Limits.MAX_ROWS);
        boolean finishedOnly = !Configs.bool(config, "includeRunning", false);
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<Map<String, Object>> rows = new ArrayList<>(this.history.runs(context.tenantId(), pipelineId, since, finishedOnly, maxRows + 1));
        if (rows.size() > maxRows) {
            rows = new ArrayList<>(rows.subList(0, maxRows));
            context.warn(String.format(Locale.ROOT, "More than %,d runs of %s in the last %d days; the oldest %,d are read.", maxRows,
                pipelineId, days, maxRows));
        }
        Limits.requireShape(rows.size(), RunHistory.COLUMNS.size(), "The runs");
        long decided = rows.stream().filter(r -> r.get("review_decision") != null).count();
        context.log(String.format(Locale.ROOT, "%d run(s) of %s in the last %d days, %d with a review decision.", rows.size(), pipelineId,
            days, decided));
        return StepResult.of(new Dataset(new ArrayList<>(RunHistory.COLUMNS), rows));
    }
}
