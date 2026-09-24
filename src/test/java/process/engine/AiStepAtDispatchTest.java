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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.ai.AiPort;
import process.ai.AiStepService;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
 * MIG-133, the AI half: the real AiStepService inside the real dispatch pass.
 *
 * DispatchDecisionTreeTest pins the tree with the AI steps as a stand-in. This class runs the actual
 * AiStepService, with the AI service behind {@link AiPort} as it is since ADR-020, so what is pinned
 * is Core's half of the seam: onError=fail aborts the dispatch and nothing reaches Kafka;
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
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private KafkaTemplate<String, String> template;

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

    private ProducerBulkEngine engine(AiStepService aiSteps) {
        return new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail, this.kafkaTemplateProvider,
            this.kafkaConnectionResolver, this.runCallbackTokens, aiSteps);
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

    private void push(ProducerBulkEngine engine, SourceJob job) throws Exception {
        Method method = ProducerBulkEngine.class.getDeclaredMethod("pushMessageToQueue", SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        try {
            method.invoke(engine, job, this.run);
        } catch (InvocationTargetException wrapper) {
            throw (Exception) wrapper.getCause();
        }
    }

    private void brokerIsReached() {
        when(this.kafkaTemplateProvider.getTemplate(any())).thenReturn(this.template);
        when(this.template.send(eq("etl.jobs"), anyString(), anyString())).thenReturn(new SettableListenableFuture<SendResult<String, String>>());
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
        verifyNoInteractions(this.kafkaTemplateProvider, this.runCallbackTokens);
    }

    @Test
    void withOnErrorContinueTheTagIsEmptiedAndTheRunIsStillSent() throws Exception {
        this.pipelineWithStep("continue", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("Daily token budget reached"));
        this.brokerIsReached();

        this.push(this.engine(this.steps()), this.dispatchableJob());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.template).send(eq("etl.jobs"), anyString(), message.capture());
        String sent = JsonParser.parseString(message.getValue()).getAsJsonObject().get("taskPayload").getAsString();
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
        verifyNoInteractions(this.kafkaTemplateProvider);
    }

    @Test
    void anUnreachableAiServiceIsAFailedStepNotARetryableDispatchFailure() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.theAiServiceAnswers(AiPort.StepResult.failed("The AI service could not be reached, so the step did not run."));

        this.push(this.engine(this.steps()), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: The AI service could not be reached, so the step did not run.", false);
        verifyNoInteractions(this.kafkaTemplateProvider);
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
        verifyNoInteractions(this.kafkaTemplateProvider);
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
     * and the throw lands in pushMessageToQueue's outer catch: the run is retried as "could not be
     * dispatched" (row 6) instead of failing once as an AI-step failure (row 5).
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
        verifyNoInteractions(this.kafkaTemplateProvider);
    }
}
