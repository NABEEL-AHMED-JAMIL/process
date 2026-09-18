package process.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.repository.*;
import process.util.EncryptionUtil;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The AI step at dispatch: the answer lands in the task's document as a tag, a retried run
 * reuses the recorded answer, and a failed step either fails the run or leaves the tag empty
 * as the pipeline says.
 */
@ExtendWith(MockitoExtension.class)
public class AiStepServiceTest {

    private static final long TENANT = 2905L;
    @Mock private PipelineRepository pipelines;
    @Mock private AiPromptRepository prompts;
    @Mock private AiModelConnectionRepository connections;
    @Mock private AiPromptRunRepository runs;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private PromptRunner runner;

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

    private AiPrompt prompt() {
        AiPrompt p = new AiPrompt();
        p.setPromptId(1000L); p.setTenantId(TENANT); p.setName("Summarise"); p.setStatus(Status.Active); p.setVersion(3);
        p.setUserTemplate("Claim {{claim_id}}: {{document_text}}"); p.setOutputMode("text");
        p.setVariables("[{\"name\":\"claim_id\",\"required\":true},{\"name\":\"document_text\",\"required\":true}]");
        return p;
    }

    private AiModelConnection connection() {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setTenantId(TENANT); c.setProvider("Ollama"); c.setDefaultModel("gemma3:1b"); c.setStatus(Status.Active); c.setIsDefault(true);
        return c;
    }

    private AiPromptRun answered(String text) {
        AiPromptRun r = new AiPromptRun(); r.setStatus("ok"); r.setOutput(text); r.setPromptVersion(3); r.setTokensIn(10); r.setTokensOut(5); r.setLatencyMs(500);
        return r;
    }

    @Test
    void aPipelineWithoutAiStepsSendsTheDocumentAsStored() {
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.emptyList());
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);
        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(out.failed()).isFalse();
        assertThat(out.payload).isSameAs(PAYLOAD);
        verifyNoInteractions(this.runner);
    }

    @Test
    void theStepReadsItsVariablesFromTheDocumentAndWritesTheAnswerAsATag() {
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(this.pipelineWithStep("fail")));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(this.prompt()));
        when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active)).thenReturn(Optional.of(this.connection()));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.empty());
        when(this.runner.run(any())).thenAnswer(inv -> {
            PromptRunner.Job job = inv.getArgument(0);
            assertThat(job.values).containsEntry("claim_id", "CLM-1").containsEntry("document_text", "notes here");
            assertThat(job.kind).isEqualTo("run"); assertThat(job.jobQueueId).isEqualTo(55L); assertThat(job.stepTag).isEqualTo("summary");
            assertThat(job.promptVersion).isEqualTo(3);
            return this.answered("Diabetes; 2 procedures & <flag>");
        });
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isFalse();
        // Escaped by the XML writer, not concatenated: the answer's '&' and '<' survive as text.
        assertThat(out.payload).contains("<summary>Diabetes; 2 procedures &amp; &lt;flag&gt;</summary>");
        assertThat(out.payload).contains("<claim_id>CLM-1</claim_id>");
        assertThat(out.notes).anySatisfy(n -> assertThat(n).contains("<summary>").contains("Summarise v3"));
    }

    @Test
    void aRetriedRunReusesTheRecordedAnswer() {
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(this.pipelineWithStep("fail")));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.of(this.answered("kept")));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(this.prompt()));
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.payload).contains("<summary>kept</summary>");
        verifyNoInteractions(this.runner);
    }

    @Test
    void aFailedStepFailsTheRunOrContinuesEmptyAsThePipelineSays() {
        AiPromptRun failed = new AiPromptRun(); failed.setStatus("failed"); failed.setError("Daily token budget reached");
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(this.prompt()));
        when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active)).thenReturn(Optional.of(this.connection()));
        when(this.runs.findByJobQueueIdAndStepTag(eq(55L), any())).thenReturn(Optional.empty());
        when(this.runner.run(any())).thenReturn(failed);
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(this.pipelineWithStep("fail")));
        AiStepService.Outcome stop = service.apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(stop.failed()).isTrue();
        assertThat(stop.failure).contains("<summary>").contains("budget");

        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(this.pipelineWithStep("continue")));
        AiStepService.Outcome go = service.apply(TENANT, "F1", 55L, PAYLOAD);
        assertThat(go.failed()).isFalse();
        assertThat(go.payload).contains("<summary/>").contains("<claim_id>CLM-1</claim_id>");
    }

    @Test
    void aPromptNoLongerActiveRefusesBeforeAnyCallAndLeavesARow() {
        AiPrompt off = this.prompt(); off.setStatus(Status.Inactive);
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(this.pipelineWithStep("fail")));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(off));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.empty());
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isTrue();
        assertThat(out.failure).contains("no longer active");
        verifyNoInteractions(this.runner);
    }

    @Test
    void aWorkerStepIsHandedOverInTheDocumentNotRun() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker"); step.setVariableMap("{\"claim_id\":\"claim_id\",\"document_text\":\"file:document\"}");
        AiPrompt prompt = this.prompt(); prompt.setPromptUuid("uuid-9");
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(p));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(prompt));
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);

        assertThat(out.failed()).isFalse();
        assertThat(out.payload).contains("<ai_step on_error=\"continue\" output=\"summary\" prompt=\"uuid-9\" version=\"3\">");
        assertThat(out.payload).contains("<var as=\"text\" from=\"claim_id\" name=\"claim_id\"/>");
        assertThat(out.payload).contains("<var as=\"file\" from=\"document\" name=\"document_text\"/>");
        verifyNoInteractions(this.runner);
    }

    @Test
    void aStepOverTheInputObjectsIsHandedOverWithObjectVariables() throws Exception {
        Pipeline p = this.pipelineWithStep("continue");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker");
        step.setVariableMap("{\"claim_id\":\"object:name\",\"document_text\":\"object:text\"}");
        AiPrompt prompt = this.prompt(); prompt.setPromptUuid("uuid-9");
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(p));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(prompt));
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        AiStepService.Outcome out = service.apply(TENANT, "F1", 55L, PAYLOAD);

        // The vars have to be in the element: without them the worker had nothing to loop over.
        assertThat(out.payload).contains("<var as=\"name\" from=\"object\" name=\"claim_id\"/>");
        assertThat(out.payload).contains("<var as=\"text\" from=\"object\" name=\"document_text\"/>");
    }

    @Test
    void aPerObjectRunIsItsOwnRowKeyedOnTheObject() {
        Pipeline p = this.pipelineWithStep("continue");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker");
        AiPrompt prompt = this.prompt(); prompt.setPromptUuid("uuid-9");
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(p));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(prompt));
        when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active)).thenReturn(Optional.of(this.connection()));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary#claims/in/a.txt")).thenReturn(Optional.empty());
        when(this.runner.run(any())).thenAnswer(inv -> {
            PromptRunner.Job job = inv.getArgument(0);
            assertThat(job.stepTag).isEqualTo("summary#claims/in/a.txt");
            return this.answered("one");
        });
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);
        Map<String, String> values = new HashMap<>(); values.put("claim_id", "claims/in/a.txt"); values.put("document_text", "claim A");
        assertThat(service.runForWorker(TENANT, "F1", 55L, "summary", "claims/in/a.txt", "uuid-9", values).getOutput()).isEqualTo("one");
    }

    @Test
    void theWorkersCallRunsOnlyAStepItWasHanded() {
        Pipeline p = this.pipelineWithStep("fail");
        PipelineField step = p.getFields().get(2); step.setRunIn("worker");
        AiPrompt prompt = this.prompt(); prompt.setPromptUuid("uuid-9");
        when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(p));
        when(this.prompts.findById(1000L)).thenReturn(Optional.of(prompt));
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        AiStepService service = new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, this.runner);

        // A tag the pipeline does not hand over, and a prompt the step does not name: refused, no call.
        assertThat(service.runForWorker(TENANT, "F1", 55L, "other", "uuid-9", Collections.emptyMap()).getError()).contains("no such step");
        assertThat(service.runForWorker(TENANT, "F1", 55L, "summary", "uuid-else", Collections.emptyMap()).getError()).contains("does not name");
        verifyNoInteractions(this.runner);

        // The right step: the worker's values go straight into the run.
        when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active)).thenReturn(Optional.of(this.connection()));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.empty());
        when(this.runner.run(any())).thenAnswer(inv -> {
            PromptRunner.Job job = inv.getArgument(0);
            assertThat(job.values).containsEntry("document_text", "the file's text, read by the worker");
            return this.answered("done");
        });
        Map<String, String> values = new HashMap<>();
        values.put("claim_id", "CLM-1"); values.put("document_text", "the file's text, read by the worker");
        assertThat(service.runForWorker(TENANT, "F1", 55L, "summary", "uuid-9", values).getOutput()).isEqualTo("done");
    }
}
