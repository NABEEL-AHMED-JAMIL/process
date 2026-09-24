package process.engine;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.ai.AiPort;
import process.ai.AiStepService;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.PipelineRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;
import process.security.TenantContext;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-133, the AI half: the real AiStepService inside the real dispatch phases -- since MIG-134/MIG-25
 * the pre-dispatch phase, which answers AI steps off the dispatcher's thread, then the dispatcher.
 *
 * DispatchDecisionTreeTest pins the tree with the AI steps as a stand-in. This class runs the actual
 * AiStepService, with the AI service behind {@link AiPort} as it is since ADR-020, so what is pinned
 * is Core's half of the seam: onError=fail aborts the dispatch and nothing is written for sending;
 * onError=continue empties the tag and dispatch proceeds; a step the AI service could not run --
 * refused, failed, or unreachable -- is a failed step (row 5, final), never a dispatch failure (row 6,
 * retried); and apply reads no TenantContext, because the scheduler thread has none.
 *
 * The business rule, verbatim: "PromptRunner.run never throws and AiStepService returns an Outcome
 * rather than raising, precisely because a throw at this seam silently reclassifies AI failures from
 * non-retryable to retryable." Since the split, a malformed prompt variable list and a connection key
 * that will not decrypt are the AI service's to report, and it reports them as a failed step: both
 * former pins are closed. One remains in Core: a step whose variable map is not JSON.
 */
@ExtendWith(MockitoExtension.class)
class AiStepAtDispatchTest {

    private static final long TENANT = 2905L;
    private static final long OTHER_TENANT = 4102L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final long PROMPT_ID = 1000L;
    private static final String STORED = "<pipeline><claim_id>CLM-1</claim_id><document>notes here</document></pipeline>";
    private static final String MAPPED = "{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private RunCallbackTokens runCallbackTokens;

    @Mock private PipelineRepository pipelines;
    @Mock private AiPort ai;

    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AiStepService steps() {
        return new AiStepService(this.pipelines, this.ai);
    }

    private DispatchPipeline engine(AiStepService aiSteps) {
        return new DispatchPipeline(this.bulkAction, this.transactionService, this.jobMail, this.runCallbackTokens, aiSteps);
    }

    /** A pipeline F1 with one server-side AI step writing <summary>. */
    private PipelineField pipelineWithStep(String onError, String variableMap) {
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineId("F1");
        pipeline.setTenantId(TENANT);
        pipeline.setStatus(Status.Active);
        PipelineField step = new PipelineField();
        step.setTagKey("summary");
        step.setLabel("AI summary");
        step.setFieldType("ai");
        step.setPosition(2);
        step.setPromptId(PROMPT_ID);
        step.setVariableMap(variableMap);
        step.setOnError(onError);
        pipeline.getFields().add(step);
        lenient().when(this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete))
            .thenReturn(Collections.singletonList(pipeline));
        return step;
    }

    private void theAiServiceAnswers(AiPort.StepResult result) {
        when(this.ai.runStep(anyLong(), anyLong(), anyString(), anyLong(), anyMap())).thenReturn(result);
    }

    private static AiPort.StepResult answered(String output) {
        AiPort.StepResult result = new AiPort.StepResult();
        result.status = "ok";
        result.output = output;
        result.promptName = "Summarise";
        result.promptVersion = 3;
        return result;
    }

    private SourceJob dispatchableJob() {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setPipelineId("F1");
        task.setTaskPayload(STORED);
        task.setSourceTaskType(type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private DispatchPipeline pushed;

    private void push(DispatchPipeline pipeline, SourceJob job) throws Exception {
        this.pushed = pipeline;
        pipeline.push(job, this.run);
    }

    private void nothingWasWrittenForSending() {
        assertThat(this.pushed.written).isEmpty();
    }

    private void assertClosedAsFailed(String sentence, boolean retried) {
        if (retried) {
            verify(this.bulkAction).scheduleRetry(this.run, sentence);
        } else {
            verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        }
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, sentence);
    }

    // ---- onError, both ways --------------------------------------------------------------------------------

    @Test
    void withOnErrorFailAFailedStepClosesTheRunAndNothingReachesKafka() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("Daily token budget reached"));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: Daily token budget reached", false);
        verifyNoInteractions(this.runCallbackTokens);
        this.nothingWasWrittenForSending();
    }

    @Test
    void withOnErrorContinueTheTagIsEmptiedAndTheRunIsStillSent() throws Exception {
        this.pipelineWithStep("continue", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("Daily token budget reached"));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        assertThat(this.pushed.lastWritten().topic).isEqualTo("etl.jobs");
        String sent = JsonParser.parseString(this.pushed.lastWritten().payload).getAsJsonObject().get("taskPayload").getAsString();
        assertThat(sent).containsPattern("<summary(/>|></summary>)").contains("<claim_id>CLM-1</claim_id>");
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID,
            "AI step <summary> failed and the pipeline continues with it empty: Daily token budget reached");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Failed));
    }

    /**
     * A model call that blew up, a refusal, and an AI service that could not be reached all come back
     * from AiPort as a failed step: final, not broker trouble.
     */
    @Test
    void aModelCallThatTimesOutIsAFailedStepNotARetryableDispatchFailure() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("Read timed out"));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: Read timed out", false);
        this.nothingWasWrittenForSending();
    }

    @Test
    void anUnreachableAiServiceIsAFailedStepNotARetryableDispatchFailure() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("The AI service could not be reached, so the step did not run."));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: The AI service could not be reached, so the step did not run.", false);
        this.nothingWasWrittenForSending();
    }

    /**
     * Formerly pinned (MIG-133): a connection key that will not decrypt made apply throw, and the run
     * was retried as if the broker were down. Since ADR-020 the key is the AI service's, which reports
     * it as a failed step, so the run fails once, as an AI-step failure.
     */
    @Test
    void anUndecryptableConnectionKeyIsNowAFailedStepNotABrokerRetry() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("The model connection's key could not be opened."));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: The model connection's key could not be opened.", false);
        this.nothingWasWrittenForSending();
    }

    // ---- the Outcome contract ---------------------------------------------------------------------------------

    /** Every failure apply was written to expect comes back as an Outcome; none is thrown. */
    @Test
    void everyAnticipatedFailureComesBackAsAnOutcome() {
        AiStepService service = this.steps();
        assertThat(service.apply(TENANT, null, QUEUE_ID, STORED).payload).as("no pipeline").isSameAs(STORED);
        assertThat(service.apply(TENANT, "F9", QUEUE_ID, STORED).payload).as("unknown pipeline").isSameAs(STORED);

        this.pipelineWithStep("fail", MAPPED);
        assertThat(service.apply(TENANT, "F1", QUEUE_ID, "<pipeline><unclosed></pipeline>").failure)
            .startsWith("The task payload is not well-formed XML, so its AI step cannot read it: ");

        this.theAiServiceAnswers(AiPort.StepResult.failed("The prompt this step names is no longer active in this workspace."));
        assertThat(service.apply(TENANT, "F1", QUEUE_ID, STORED).failure)
            .isEqualTo("AI step <summary> failed: The prompt this step names is no longer active in this workspace.");
    }

    /** The tenant is the argument, never the context: another tenant's context changes nothing. */
    @Test
    void applyReadsNoTenantContext() {
        TenantContext.set(OTHER_TENANT, "TENANT_ADMIN", 1L, "someone@example.com");
        this.pipelineWithStep("fail", MAPPED);
        when(this.ai.runStep(eq(TENANT), eq(QUEUE_ID), eq("summary"), eq(PROMPT_ID), anyMap())).thenReturn(answered("diabetes"));

        AiStepService.Outcome outcome = this.steps().apply(TENANT, "F1", QUEUE_ID, STORED);

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.payload).contains("<summary>diabetes</summary>");
        verify(this.pipelines).findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete);
    }

    // ---- where the contract does not hold -------------------------------------------------------------------

    /**
     * apply still THROWS, rather than returning a failed Outcome, on one input it does not anticipate:
     * a step whose variable map is not JSON. That is Core's half, read before the AI service is asked,
     * and the throw lands in the pre-dispatch phase's catch -- where pushMessageToQueue's outer catch
     * used to be, with the same classification: the run is retried as "could not be dispatched" (row 6)
     * instead of failing once as an AI-step failure (row 5).
     */
    @Test
    @Tag("pinned-unreviewed")
    void aMalformedVariableMapThrowsAndIsRetriedAsIfTheBrokerWereDown() throws Exception {
        this.pipelineWithStep("fail", "{\"claim_id\": ");
        assertThatThrownBy(() -> this.steps().apply(TENANT, "F1", QUEUE_ID, STORED)).isInstanceOf(JsonSyntaxException.class);

        this.push(this.engine(this.steps()), this.dispatchableJob());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).scheduleRetry(eq(this.run), reason.capture());
        assertThat(reason.getValue()).startsWith("Job 1196 could not be dispatched: ");
        verify(this.ai, never()).runStep(any(), any(), any(), any(), any());
        this.nothingWasWrittenForSending();
    }
}
