package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.ai.AiPort;
import process.model.dto.AiPromptDto;
import process.pipeline.Dataset;
import process.pipeline.StepResult;
import process.pipeline.backing.Fakes;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * ZT-S4 (showcase, 2026-10-26): a step that names no Variables sent the prompt EMPTY ("Patient  (). Reason: ."), so the model
 * answered from nothing -- though the step says its rows' columns fill the prompt's variables. Now each prompt variable the
 * step does not name is filled from the row's column of that name, then from the run's placeholders; a required variable
 * still empty fails that row's call (never sent), by the step's on-error rule; an optional one goes blank and the step says so.
 */
class AiPromptVariablesTest {

    private final AiPort ai = mock(AiPort.class);
    private final AiPromptStepTask task = new AiPromptStepTask(this.ai, new Fakes.Buckets());

    private static AiPromptDto.Variable variable(String name, boolean required) {
        AiPromptDto.Variable v = new AiPromptDto.Variable();
        v.name = name;
        v.type = "text";
        v.required = required;
        return v;
    }

    private void prompt(AiPromptDto.Variable... variables) throws Exception {
        AiPort.PromptInfo info = new AiPort.PromptInfo();
        info.promptId = 41L;
        info.name = "Discharge summary";
        info.status = "Active";
        info.variables = Arrays.asList(variables);
        Map<Long, AiPort.PromptInfo> found = new LinkedHashMap<>();
        found.put(41L, info);
        when(this.ai.prompts(anyCollection())).thenReturn(found);
        AiPort.StepResult ok = new AiPort.StepResult();
        ok.status = "ok";
        ok.output = "{\"diagnosis\":\"recorded\"}";
        when(this.ai.runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any())).thenReturn(ok);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> sent(int calls) {
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(this.ai, times(calls)).runRowStep(anyLong(), anyLong(), anyString(), anyString(), eq(41L), values.capture(), any(), any(), any());
        return values.getAllValues();
    }

    @Test
    void aVariableTheStepDoesNotNameIsFilledFromTheRowsColumnThenTheRun() throws Exception {
        this.prompt(variable("name", false), variable("reason", true), variable("run", false));
        TaskContext context = TaskContext.of(config("promptId", 41), Collections.singletonList(row("name", "Elena Marsh", "reason", "Foot wound")));

        this.task.run(context);

        Map<String, String> values = this.sent(1).get(0);
        assertThat(values).containsEntry("name", "Elena Marsh").containsEntry("reason", "Foot wound");
        assertThat(values.get("run")).isEqualTo(String.valueOf(context.jobQueueId()));
    }

    @Test
    void whatTheStepNamesWinsOverTheColumn() throws Exception {
        this.prompt(variable("reason", true));
        TaskContext context = TaskContext.of(config("promptId", 41, "values", config("reason", "{{diagnosis}}")),
            Collections.singletonList(row("reason", "old", "diagnosis", "Cellulitis")));

        this.task.run(context);

        assertThat(this.sent(1).get(0)).containsEntry("reason", "Cellulitis");
    }

    @Test
    void aRequiredVariableWithNoValueFailsThatRowsCallUnsentByTheRule() throws Exception {
        this.prompt(variable("name", false), variable("reason", true));
        List<Map<String, Object>> rows = Arrays.asList(row("name", "A", "reason", "Foot wound"), row("name", "B", "reason", " "));

        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("promptId", 41), rows)))
            .hasMessage("Row 2: Prompt variable 'reason' has no value: map it in Variables or add a column named reason");

        TaskContext skipping = TaskContext.of(config("promptId", 41, "onError", "skip"), rows);
        Dataset out = this.task.run(skipping).getOutput();
        assertThat(out.getRows()).hasSize(1);
        assertThat(skipping.itemEvents).anyMatch(e -> e.endsWith(
            "failed {name=B, reason= }: Prompt variable 'reason' has no value: map it in Variables or add a column named reason"));
        // Row 1 of the first run and row 1 of the second: never row 2.
        this.sent(2).forEach(v -> assertThat(v).containsEntry("reason", "Foot wound"));
    }

    @Test
    void anOptionalVariableWithNoValueGoesBlankAndTheStepSaysSo() throws Exception {
        this.prompt(variable("name", false), variable("allergies", false));
        TaskContext context = TaskContext.of(config("promptId", 41), Arrays.asList(row("name", "A"), row("name", "B")));

        this.task.run(context);

        this.sent(2).forEach(v -> assertThat(v).containsEntry("allergies", ""));
        assertThat(context.lines).contains("WARN Prompt variable 'allergies' had no value in 2 of 2 row(s); it was sent blank.");
    }

    @Test
    void aRequiredVariableNeitherNamedNorAColumnIsSaidOnceBeforeAnyCall() throws Exception {
        this.prompt(variable("reason", true));
        TaskContext context = TaskContext.of(config("promptId", 41, "onError", "null"), Arrays.asList(row("name", "A"), row("name", "B")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getRows()).hasSize(2);
        verify(this.ai, never()).runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any());
        assertThat(context.lines).contains("WARN Prompt variable 'reason' is required, not mapped in Variables, and the input has no column "
            + "named reason: no row's call can be sent.");
    }

    @Test
    void aPromptAiServiceCannotDescribeKeepsTheStepsOwnVariables() throws Exception {
        when(this.ai.prompts(anyCollection())).thenThrow(new AiPort.AiUnavailableException("down", null));
        AiPort.StepResult ok = new AiPort.StepResult();
        ok.status = "ok";
        ok.output = "fine";
        when(this.ai.runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any())).thenReturn(ok);
        TaskContext context = TaskContext.of(config("promptId", 41, "values", config("note", "{{note}}")), Collections.singletonList(row("note", "x")));

        this.task.run(context);

        assertThat(this.sent(1).get(0)).containsOnlyKeys("note");
    }

    /**
     * ZT-AI66 (AI eval P-step-902, Riverside, 2026-10-27): rows never sent for a required variable with no value were said only in
     * the step's warnings; its summary read "Completed: 3 in, 3 out". The summary says how many and why, and each item why.
     */
    @Test
    void theStepsSummarySaysRowsWereNotSentForARequiredVariableWithNoValue() throws Exception {
        this.prompt(variable("mobility", true), variable("pain_score", true));
        TaskContext context = TaskContext.of(config("promptId", 41, "onError", "null"),
            Arrays.asList(row("pain_score", "7"), row("pain_score", "4"), row("pain_score", "9")));

        StepResult result = this.task.run(context);

        assertThat(result.getNote()).isEqualTo("3 of 3 row(s) not sent to the model: the required prompt variable 'mobility' had no value "
            + "(map it in Variables or add a column named mobility).");
        assertThat(context.itemEvents).filteredOn(e -> e.contains(" failed ")).hasSize(3)
            .allMatch(e -> e.contains("Prompt variable 'mobility' has no value"));
        verify(this.ai, never()).runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any());

        this.prompt(variable("pain_score", true));
        assertThat(this.task.run(TaskContext.of(config("promptId", 41), Collections.singletonList(row("pain_score", "7")))).getNote())
            .as("every row sent: nothing more to say").isNull();
    }
}
