package process.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.step;

/**
 * Console review 2026-10-07: what a definition's steps point at is read from the Task Registry's schema formats, not from
 * task names -- so every task that takes a prompt or an API request counts, and nothing else does.
 */
class StepReferencesTest {

    private final StepReferences references = new StepReferences(new AllTasks().tasks());

    private final PipelineDefinition definition = Definitions.of(
        step("read", "read_api", config("requestId", 1083, "version", 2)),
        step("rx", "enrich", config("requestId", "1087", "into", "rx")),
        step("rows", "sample", config("rows", Arrays.asList(config("requestId", 1090)), "limit", 1083)),
        step("label", "ai_prompt", config("promptId", 1073, "image", config("bucket", "b", "keyColumn", "k"))),
        step("summary", "ai_prompt", config("promptId", 1075)),
        step("measure", "measure_image", config("bucket", "b", "keyColumn", "k")));

    @Test
    void apiRequestsAreTheStepsWhoseSchemaSaysApiRequest() {
        List<StepReferences.Ref> refs = this.references.of(this.definition, StepReferences.API_REQUEST);
        assertThat(refs).extracting(r -> r.stepKey() + "=" + r.id).containsExactly("read=1083", "rx=1087");
        assertThat(refs.get(0).config()).containsEntry("version", 2);
    }

    @Test
    void aiStepsAreTheStepsBackedByAiServiceThatNameAPrompt() {
        assertThat(this.references.aiSteps(this.definition).stream().map(r -> r.label() + ":" + r.id).collect(Collectors.toList()))
            .containsExactly("label:1073", "summary:1075");
    }

    @Test
    void aLegacyDefinitionHasNoEngineReferences() {
        assertThat(this.references.of(PipelineDefinition.legacy("P"), StepReferences.PROMPT)).isEmpty();
        assertThat(this.references.of(null, StepReferences.PROMPT)).isEmpty();
    }

    @Test
    void anIdIsAPositiveWholeNumber() {
        assertThat(StepReferences.idOf(12)).isEqualTo(12L);
        assertThat(StepReferences.idOf(12.0)).isEqualTo(12L);
        assertThat(StepReferences.idOf(" 12 ")).isEqualTo(12L);
        assertThat(StepReferences.idOf(12.5)).isNull();
        assertThat(StepReferences.idOf(0)).isNull();
        assertThat(StepReferences.idOf("{{id}}")).isNull();
        assertThat(StepReferences.idOf(null)).isNull();
    }
}
