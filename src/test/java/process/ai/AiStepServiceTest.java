package process.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.repository.PipelineRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The AI step at dispatch, Core's half (ADR-020): the answer lands in the task's document as a tag,
 * a retried run reuses the recorded answer, a failed step either fails the run or leaves the tag
 * empty as the pipeline says, and a worker step goes into the document with the prompt's uuid and
 * version. Running a step is the AI service's, behind {@link AiPort}.
 */
@ExtendWith(MockitoExtension.class)
public class AiStepServiceTest {

    private static final long TENANT = 2905L;
    @Mock private PipelineRepository pipelines;
    @Mock private AiPort ai;

    private static final String PAYLOAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<pipeline>\n  <claim_id>CLM-1</claim_id>\n  <document>notes here</document>\n</pipeline>";

    private Pipeline pipelineWithStep(String onError) {
        Pipeline p = new Pipeline();
        p.setPipelineId("F1"); p.setTenantId(TENANT); p.setStatus(Status.Active);
        PipelineField a = new PipelineField(); a.setTagKey("claim_id"); a.setLabel("Claim"); a.setFieldType("text"); a.setPosition(0);
        PipelineField b = new PipelineField(); b.setTagKey("document"); b.setLabel("Doc"); b.setFieldType("text"); b.setPosition(1);
        PipelineField step = new PipelineField(); step.setTagKey("summary"); step.setLabel("AI summary"); step.setFieldType("ai"); step.setPosition(2);
        step.setPromptId(1000L); step.setVariableMap("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}"); step.setOnError(onError);
        p.getFields().addAll(Arrays.asList(a, b, step));
        return p;
    }

    private static Map<Long, AiPort.PromptInfo> catalogue(String status, Long tenantId) {
        AiPort.PromptInfo p = new AiPort.PromptInfo();
        p.promptId = 1000L; p.promptUuid = "uuid-9"; p.name = "Summarise"; p.version = 3; p.status = status; p.tenantId = tenantId;
        return Collections.singletonMap(1000L, p);
    }

    private static AiPort.StepResult answered(String text, boolean reused) {
        AiPort.StepResult r = new AiPort.StepResult();
        r.status = "ok"; r.output = text; r.promptName = "Summarise"; r.promptVersion = 3; r.tokensIn = 10; r.tokensOut = 5; r.latencyMs = 500;
        r.reused = reused;
        return r;
    }

    private void thePipelineIs(Pipeline p) {
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(p));
    }

    @Test
    void aPipelineWithoutAiStepsSendsTheDocumentAsStored() {
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.emptyList());
        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(out.failed()).isFalse();
        assertThat(out.payload).isSameAs(PAYLOAD);
        verifyNoInteractions(this.ai);
    }

    @Test
    void theStepReadsItsVariablesFromTheDocumentAndWritesTheAnswerAsATag() throws Exception {
        thePipelineIs(this.pipelineWithStep("fail"));
        when(this.ai.runStep(eq(TENANT), eq(55L), eq("summary"), eq(1000L), anyMap())).thenAnswer(inv -> {
            Map<String, String> values = inv.getArgument(4);
            assertThat(values).containsEntry("claim_id", "CLM-1").containsEntry("document_text", "notes here");
            return answered("Diabetes; 2 procedures & <flag>", false);
        });

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isFalse();
        // Escaped by the XML writer, not concatenated: the answer's '&' and '<' survive as text.
        assertThat(out.payload).contains("<summary>Diabetes; 2 procedures &amp; &lt;flag&gt;</summary>");
        assertThat(out.payload).contains("<claim_id>CLM-1</claim_id>");
        assertThat(out.notes).anySatisfy(n -> assertThat(n).contains("<summary>").contains("Summarise v3"));
    }

    @Test
    void aRetriedRunReusesTheRecordedAnswerAndSaysSo() {
        thePipelineIs(this.pipelineWithStep("fail"));
        when(this.ai.runStep(anyLong(), anyLong(), anyString(), anyLong(), anyMap())).thenReturn(answered("kept", true));

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.payload).contains("<summary>kept</summary>");
        assertThat(out.notes).anySatisfy(n -> assertThat(n).contains("reused the answer already recorded"));
    }

    @Test
    void aFailedStepFailsTheRunOrContinuesEmptyAsThePipelineSays() {
        when(this.ai.runStep(anyLong(), anyLong(), anyString(), anyLong(), anyMap()))
            .thenReturn(AiPort.StepResult.failed("Daily token budget reached"));
        AiStepService service = new AiStepService(this.pipelines, this.ai);

        thePipelineIs(this.pipelineWithStep("fail"));
        AiStepService.Outcome stop = service.apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(stop.failed()).isTrue();
        assertThat(stop.failure).contains("<summary>").contains("budget");

        thePipelineIs(this.pipelineWithStep("continue"));
        AiStepService.Outcome go = service.apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(go.failed()).isFalse();
        assertThat(go.payload).contains("<summary/>").contains("<claim_id>CLM-1</claim_id>");
    }

    /** An AI service that cannot be reached is a failed step, so on-error still decides. */
    @Test
    void anUnreachableAiServiceIsAFailedStepUnderTheStepsOwnRule() {
        thePipelineIs(this.pipelineWithStep("continue"));
        when(this.ai.runStep(anyLong(), anyLong(), anyString(), anyLong(), anyMap()))
            .thenReturn(AiPort.StepResult.failed("The AI service could not be reached, so the step did not run."));

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isFalse();
        assertThat(out.payload).contains("<summary/>");
        assertThat(out.notes).anySatisfy(n -> assertThat(n).contains("could not be reached"));
    }

    @Test
    void aWorkerStepIsHandedOverInTheDocumentNotRun() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker"); step.setVariableMap("{\"claim_id\":\"claim_id\",\"document_text\":\"file:document\"}");
        thePipelineIs(p);
        when(this.ai.prompts(any())).thenReturn(catalogue("Active", TENANT));

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isFalse();
        assertThat(out.payload).contains("<ai_step on_error=\"continue\" output=\"summary\" prompt=\"uuid-9\" version=\"3\">");
        assertThat(out.payload).contains("<var as=\"text\" from=\"claim_id\" name=\"claim_id\"/>");
        assertThat(out.payload).contains("<var as=\"file\" from=\"document\" name=\"document_text\"/>");
        verify(this.ai, never()).runStep(any(), any(), any(), any(), any());
    }

    @Test
    void aStepOverTheInputObjectsIsHandedOverWithObjectVariables() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker");
        step.setVariableMap("{\"claim_id\":\"object:name\",\"document_text\":\"object:text\"}");
        thePipelineIs(p);
        when(this.ai.prompts(any())).thenReturn(catalogue("Active", TENANT));

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        // The vars have to be in the element: without them the worker had nothing to loop over.
        assertThat(out.payload).contains("<var as=\"name\" from=\"object\" name=\"claim_id\"/>");
        assertThat(out.payload).contains("<var as=\"text\" from=\"object\" name=\"document_text\"/>");
    }

    @Test
    void aWorkerStepWhosePromptIsInactiveOrAnotherWorkspacesFailsTheRun() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        p.getFields().get(2).setRunIn("worker");
        thePipelineIs(p);
        AiStepService service = new AiStepService(this.pipelines, this.ai);

        when(this.ai.prompts(any())).thenReturn(catalogue("Inactive", TENANT));
        assertThat(service.apply(TENANT, "F1", 55L, PAYLOAD).failure).contains("no longer active");
        when(this.ai.prompts(any())).thenReturn(catalogue("Active", 1L));
        assertThat(service.apply(TENANT, "F1", 55L, PAYLOAD).failure).contains("no longer active");
    }

    /** Handing a worker step over needs its prompt's uuid; without AI there is nothing to hand. */
    @Test
    void aWorkerStepWithTheAiServiceDownFailsTheRunBeforeDispatch() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        p.getFields().get(2).setRunIn("worker");
        thePipelineIs(p);
        when(this.ai.prompts(any())).thenThrow(new AiPort.AiUnavailableException("down", null));

        AiStepService.Outcome out = new AiStepService(this.pipelines, this.ai).apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isTrue();
        assertThat(out.failure).contains("could not be reached");
    }
}
