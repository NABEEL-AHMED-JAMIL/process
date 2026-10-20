package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.RunHistory;
import process.pipeline.registry.TaskSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static process.pipeline.Definitions.config;

/**
 * 2026-10-19: Read runs answers a pipeline's runs in the run's workspace with their reviews, in the fixed columns a report
 * joins on (run_id); empty pipelineId reads the run's own pipeline; more runs than maxRows warns and keeps the oldest.
 * An administrator's task: a review names its reviewer and their comment.
 */
class ReadRunsStepTaskTest {

    private final Fakes.Runs runs = new Fakes.Runs();
    private final ReadRunsStepTask task = new ReadRunsStepTask(this.runs);

    private static Map<String, Object> run(long id, String pipeline, String decision) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : RunHistory.COLUMNS) {
            row.put(column, null);
        }
        row.put("run_id", id);
        row.put("pipeline_id", pipeline);
        row.put("status", "Completed");
        row.put("review_status", decision == null ? "pending" : decision);
        row.put("review_decision", decision);
        return row;
    }

    @Test
    void theNamedPipelinesRunsAreReadInTheFixedColumns() throws Exception {
        this.runs.rows.add(run(1, "SCANS", "approved"));
        this.runs.rows.add(run(2, "OTHER", "approved"));
        this.runs.rows.add(run(3, "SCANS", null));
        TaskContext context = TaskContext.of(config("pipelineId", " SCANS ", "days", 7), Collections.emptyList());

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactlyElementsOf(RunHistory.COLUMNS);
        assertThat(out.getRows()).extracting(r -> r.get("run_id")).containsExactly(1L, 3L);
        assertThat(this.runs.lastTenant).isEqualTo(TaskContext.TENANT);
        assertThat(this.runs.lastPipeline).isEqualTo("SCANS");
        assertThat(this.runs.lastFinishedOnly).isTrue();
        assertThat(Duration.between(this.runs.lastSince, Instant.now()).toHours()).isCloseTo(7 * 24L, within(1L));
        assertThat(context.lines).anyMatch(line -> line.contains("INFO " + "2 run(s) of SCANS in the last 7 days, 1 with a review decision"));
    }

    @Test
    void noPipelineIdReadsTheRunsOwnPipeline() throws Exception {
        TaskContext context = TaskContext.of(config("includeRunning", true), Collections.emptyList());
        this.runs.rows.add(run(9, context.pipelineId(), "rejected"));

        Dataset out = this.task.run(context).getOutput();

        assertThat(this.runs.lastPipeline).isEqualTo(context.pipelineId());
        assertThat(this.runs.lastFinishedOnly).isFalse();
        assertThat(out.getRows()).hasSize(1);
    }

    @Test
    void moreRunsThanMaxRowsKeepsTheOldestAndWarns() throws Exception {
        for (long id = 1; id <= 5; id++) {
            this.runs.rows.add(run(id, "SCANS", "approved"));
        }
        TaskContext context = TaskContext.of(config("pipelineId", "SCANS", "maxRows", 3), Collections.emptyList());

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getRows()).extracting(r -> r.get("run_id")).containsExactly(1L, 2L, 3L);
        assertThat(this.runs.lastLimit).isEqualTo(4);
        assertThat(context.lines).anyMatch(line -> line.contains("WARN " + "More than 3 runs of SCANS"));
    }

    @Test
    void itIsATenantAdministratorsTask() {
        assertThat(this.task.spec().requiredRole()).isEqualTo(TaskSpec.ROLE_ADMIN);
        assertThat(this.task.spec().kind().label()).isEqualTo("Read");
    }
}
