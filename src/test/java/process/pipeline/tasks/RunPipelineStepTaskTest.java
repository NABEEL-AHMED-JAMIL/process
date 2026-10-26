package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineCalls;
import process.pipeline.RunResults;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-424: Run pipeline -- call another pipeline once or per row, with parameters from the row, and take its answer as rows. */
class RunPipelineStepTaskTest {

    private final PipelineCalls calls = mock(PipelineCalls.class);
    private final RunPipelineStepTask task = new RunPipelineStepTask(this.calls);

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static PipelineCalls.Called called(long runId, String status, Dataset rows) {
        PipelineCalls.Called c = new PipelineCalls.Called();
        c.runId = runId;
        c.status = status;
        c.rows = rows;
        c.about.step = "careplan";
        c.about.rowCount = rows.size();
        return c;
    }

    @Test
    void onceTheCalledRunsRowsAreTheStepsOutput() throws Exception {
        Map<String, Object> config = row("jobId", 47, "parameters", row("region", "{{param.region}}", "mrn", "{{mrn}}"));
        TaskContext context = TaskContext.of(config, Collections.singletonList(row("mrn", "MRN-1")));
        context.runValues.put("param.region", "north");
        Dataset answer = new Dataset(Arrays.asList("mrn", "risk"), Arrays.asList(row("mrn", "MRN-1", "risk", "High")));
        when(this.calls.call(eq(TaskContext.TENANT), eq(TaskContext.RUN), eq(47L), eq(row("region", "north", "mrn", "MRN-1")), any(),
            eq(RunPipelineStepTask.DEFAULT_MAX_ROWS))).thenReturn(called(9001L, "Completed", answer));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("mrn", "risk");
        assertThat(out.getRows()).containsExactly(row("mrn", "MRN-1", "risk", "High"));
        assertThat(context.lines).anyMatch(l -> l.contains("Run 9001 of pipeline 47: Completed"));
    }

    @Test
    void onceACalledRunThatDidNotCompleteFailsTheStepInWords() throws Exception {
        TaskContext context = TaskContext.of(row("jobId", 47), Collections.<Map<String, Object>>emptyList());
        PipelineCalls.Called failed = called(9002L, "Failed", Dataset.EMPTY);
        failed.message = "Step 2 of 3 (score) failed: no model.";
        when(this.calls.call(anyLong(), anyLong(), eq(47L), any(), any(), anyInt())).thenReturn(failed);
        assertThatThrownBy(() -> this.task.run(context)).hasMessage(
            "The called run 9002 of pipeline 47 did not complete (Failed): Step 2 of 3 (score) failed: no model.");
    }

    @Test
    void eachRowCallsWithItsOwnValuesAndCarriesItsAnswerAndOutcome() throws Exception {
        Map<String, Object> config = row("jobId", 47, "mode", "each", "onError", "continue", "parameters", row("mrn", "{{mrn}}"));
        TaskContext context = TaskContext.of(config, Arrays.asList(row("mrn", "MRN-1"), row("mrn", "MRN-2"), row("mrn", "MRN-3")));
        when(this.calls.call(anyLong(), anyLong(), eq(47L), eq(row("mrn", "MRN-1")), any(), eq(1)))
            .thenReturn(called(9011L, "Completed", Dataset.of(Collections.singletonList(row("eligible", true, "mrn", "ignored")))));
        PipelineCalls.Called failed = called(9012L, "Failed", Dataset.EMPTY);
        failed.message = "no such patient";
        when(this.calls.call(anyLong(), anyLong(), eq(47L), eq(row("mrn", "MRN-2")), any(), eq(1))).thenReturn(failed);
        when(this.calls.call(anyLong(), anyLong(), eq(47L), eq(row("mrn", "MRN-3")), any(), eq(1)))
            .thenThrow(new PipelineCalls.Refused("A run of pipeline 47 this run called is still in flight."));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("mrn", "called_run_id", "called_status", "called_ok", "called_error", "eligible");
        assertThat(out.getRows().get(0)).containsEntry("mrn", "MRN-1").containsEntry("eligible", true).containsEntry("called_ok", true)
            .containsEntry("called_run_id", 9011L).containsEntry("called_error", null);
        assertThat(out.getRows().get(1)).containsEntry("called_ok", false).containsEntry("called_status", "Failed")
            .containsEntry("called_error", "run 9012 Failed: no such patient");
        assertThat(out.getRows().get(2)).containsEntry("called_ok", false)
            .containsEntry("called_error", "A run of pipeline 47 this run called is still in flight.");
        // Each row's item, on the step's thread, in row order.
        assertThat(context.itemEvents).hasSize(5);
        assertThat(context.itemEvents.get(2)).contains("failed").contains("no such patient");
    }

    @Test
    void eachFailsOnTheFirstCallThatDoesNotCompleteUnlessContinue() throws Exception {
        Map<String, Object> config = row("jobId", 47, "mode", "each", "parameters", row("mrn", "{{mrn}}"));
        TaskContext context = TaskContext.of(config, Arrays.asList(row("mrn", "MRN-1"), row("mrn", "MRN-2")));
        PipelineCalls.Called failed = called(9021L, "Failed", Dataset.EMPTY);
        when(this.calls.call(anyLong(), anyLong(), eq(47L), any(), any(), eq(1))).thenReturn(failed);
        assertThatThrownBy(() -> this.task.run(context)).hasMessageStartingWith("Call 1 of 2 did not complete: run 9021 Failed");
    }

    @Test
    void eachRefusesMoreRowsThanItsCallsBeforeAnyIsMade() throws Exception {
        Map<String, Object> config = row("jobId", 47, "mode", "each", "maxCalls", 1);
        TaskContext context = TaskContext.of(config, Arrays.asList(row("mrn", "MRN-1"), row("mrn", "MRN-2")));
        assertThatThrownBy(() -> this.task.run(context)).hasMessageStartingWith("The input has 2 rows; Run pipeline makes at most 1 calls");
        verify(this.calls, never()).call(anyLong(), anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void theStepsSettingsAreCheckedAtSave() {
        List<DefinitionProblem> problems = this.task.check(row("jobId", 47, "mode", "each", "parameters", row("Bad-Name", "x"),
            "fields", Arrays.asList(row("path", "risk", "as", "called_ok"))));
        assertThat(problems).extracting(DefinitionProblem::getPath).contains("parameters.Bad-Name", "fields[0].as");
        assertThat(this.task.check(row("jobId", 47, "fields", Arrays.asList(row("path", "summary.risk", "as", "risk"))))).isEmpty();
        assertThat(RunResults.FINAL).isEqualTo("final");
    }
}
