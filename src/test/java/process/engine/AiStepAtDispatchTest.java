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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.ai.AiProviderGateway;
import process.ai.AiStepService;
import process.ai.PromptRunner;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.model.pojo.AiPromptRun;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.AiModelConnectionRepository;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.PipelineRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;
import process.security.TenantContext;
import process.util.EncryptionUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * AiStepService -- and, where it matters, the actual PromptRunner -- so what is pinned is what the
 * migration will move: onError=fail aborts the dispatch and nothing reaches Kafka; onError=continue
 * empties the tag and dispatch proceeds; a model call that throws is a failed step (row 5, final),
 * never a dispatch failure (row 6, retried); and apply reads no TenantContext, because the scheduler
 * thread has none -- the tenant is passed in, and must keep being passed in.
 *
 * The business rule, verbatim: "PromptRunner.run never throws and AiStepService returns an Outcome
 * rather than raising, precisely because a throw at this seam silently reclassifies AI failures from
 * non-retryable to retryable." The two pinned-unreviewed tests show that the rule does not hold
 * today: apply throws on inputs it does not anticipate, and those runs are retried as broker trouble.
 */
@ExtendWith(MockitoExtension.class)
class AiStepAtDispatchTest {

    private static final long TENANT = 2905L;
    private static final long OTHER_TENANT = 4102L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final long PROMPT_ID = 1000L;
    private static final String STORED = "<pipeline><claim_id>CLM-1</claim_id><document>notes here</document></pipeline>";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private KafkaTemplate<String, String> template;

    @Mock private PipelineRepository pipelines;
    @Mock private AiPromptRepository prompts;
    @Mock private AiModelConnectionRepository connections;
    @Mock private AiPromptRunRepository runs;
    @Mock private PromptRunner runner;
    @Mock private AiProviderGateway gateway;

    private EncryptionUtil encryptionUtil;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.encryptionUtil = keyed(randomKey());
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        lenient().when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(this.runs.findByJobQueueIdAndStepTag(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static EncryptionUtil keyed(String base64Key) {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "base64Key", base64Key);
        return util;
    }

    private AiStepService steps(PromptRunner withRunner) {
        return new AiStepService(this.pipelines, this.prompts, this.connections, this.runs, this.encryptionUtil, withRunner);
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

    private AiPrompt activePrompt(Long tenantId, String variables) {
        AiPrompt prompt = new AiPrompt();
        prompt.setPromptId(PROMPT_ID);
        prompt.setTenantId(tenantId);
        prompt.setName("Summarise");
        prompt.setStatus(Status.Active);
        prompt.setVersion(3);
        prompt.setUserTemplate("Claim {{claim_id}}: {{document_text}}");
        prompt.setOutputMode("text");
        prompt.setVariables(variables);
        lenient().when(this.prompts.findById(PROMPT_ID)).thenReturn(Optional.of(prompt));
        return prompt;
    }

    private AiModelConnection defaultConnection(String apiKey) {
        AiModelConnection connection = new AiModelConnection();
        connection.setConnectionId(7L);
        connection.setTenantId(TENANT);
        connection.setName("Ollama");
        connection.setProvider("Ollama");
        connection.setDefaultModel("gemma3:1b");
        connection.setStatus(Status.Active);
        connection.setIsDefault(true);
        connection.setApiKey(apiKey);
        lenient().when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active))
            .thenReturn(Optional.of(connection));
        return connection;
    }

    private static final String MAPPED = "{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}";
    private static final String VARIABLES = "[{\"name\":\"claim_id\",\"required\":true},{\"name\":\"document_text\",\"required\":true}]";

    private static AiPromptRun failedRun(String error) {
        AiPromptRun failed = new AiPromptRun();
        failed.setStatus("failed");
        failed.setError(error);
        return failed;
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
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(null);
        when(this.runner.run(any())).thenReturn(failedRun("Daily token budget reached"));

        this.push(this.engine(this.steps(this.runner)), this.dispatchableJob());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: Daily token budget reached", false);
        verifyNoInteractions(this.kafkaTemplateProvider, this.runCallbackTokens);
    }

    @Test
    void withOnErrorContinueTheTagIsEmptiedAndTheRunIsStillSent() throws Exception {
        this.pipelineWithStep("continue", MAPPED);
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(null);
        when(this.runner.run(any())).thenReturn(failedRun("Daily token budget reached"));
        this.brokerIsReached();

        this.push(this.engine(this.steps(this.runner)), this.dispatchableJob());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.template).send(eq("etl.jobs"), anyString(), message.capture());
        String sent = JsonParser.parseString(message.getValue()).getAsJsonObject().get("taskPayload").getAsString();
        assertThat(sent).containsPattern("<summary(/>|></summary>)").contains("<claim_id>CLM-1</claim_id>");
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID,
            "AI step <summary> failed and the pipeline continues with it empty: Daily token budget reached");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Failed));
    }

    /**
     * PromptRunner.run never throws: a model call that blows up is a failed STEP, final, not broker
     * trouble. Both of its catches: an I/O failure from the provider call, and a refusal it raised itself.
     */
    @Test
    void aModelCallThatTimesOutIsAFailedStepNotARetryableDispatchFailure() throws Exception {
        this.modelCallFails(new SocketTimeoutException("Read timed out"));

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: Read timed out", false);
        verifyNoInteractions(this.kafkaTemplateProvider);
    }

    @Test
    void aModelCallTheRunnerRefusesIsAFailedStepNotARetryableDispatchFailure() throws Exception {
        this.modelCallFails(new IllegalStateException("The answer is not the JSON the prompt expects"));

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: The answer is not the JSON the prompt expects", false);
        verifyNoInteractions(this.kafkaTemplateProvider);
    }

    private void modelCallFails(Exception thrown) throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(null);
        when(this.gateway.chat(any())).thenThrow(thrown);
        PromptRunner realRunner = new PromptRunner(this.gateway, this.runs);

        this.push(this.engine(this.steps(realRunner)), this.dispatchableJob());
    }

    // ---- the Outcome contract ---------------------------------------------------------------------------------

    /** Every failure apply was written to expect comes back as an Outcome; none is thrown. */
    @Test
    void everyAnticipatedFailureComesBackAsAnOutcome() {
        AiStepService service = this.steps(this.runner);
        assertThat(service.apply(TENANT, null, QUEUE_ID, STORED).payload).as("no pipeline").isSameAs(STORED);
        assertThat(service.apply(TENANT, "F9", QUEUE_ID, STORED).payload).as("unknown pipeline").isSameAs(STORED);

        this.pipelineWithStep("fail", MAPPED);
        assertThat(service.apply(TENANT, "F1", QUEUE_ID, "<pipeline><unclosed></pipeline>").failure)
            .startsWith("The task payload is not well-formed XML, so its AI step cannot read it: ");

        when(this.prompts.findById(PROMPT_ID)).thenReturn(Optional.empty());
        assertThat(service.apply(TENANT, "F1", QUEUE_ID, STORED).failure)
            .isEqualTo("AI step <summary> failed: The prompt this step names is no longer active in this workspace.");

        this.activePrompt(TENANT, VARIABLES);
        when(this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active)).thenReturn(Optional.empty());
        assertThat(service.apply(TENANT, "F1", QUEUE_ID, STORED).failure).isEqualTo("AI step <summary> failed: "
            + "No active model connection for this prompt: name one on it or set a workspace default.");
        verifyNoInteractions(this.runner);
    }

    /** The tenant is the argument, never the context: another tenant's context changes nothing. */
    @Test
    void applyReadsNoTenantContext() {
        TenantContext.set(OTHER_TENANT, "TENANT_ADMIN", 1L, "someone@example.com");
        this.pipelineWithStep("fail", MAPPED);
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(null);
        AiPromptRun answered = new AiPromptRun();
        answered.setStatus("ok");
        answered.setOutput("diabetes");
        when(this.runner.run(any())).thenReturn(answered);

        AiStepService.Outcome outcome = this.steps(this.runner).apply(TENANT, "F1", QUEUE_ID, STORED);

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.payload).contains("<summary>diabetes</summary>");
        verify(this.pipelines).findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete);
        verify(this.connections).findFirstByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active);
    }

    // ---- where the contract does not hold -------------------------------------------------------------------

    /**
     * apply THROWS, rather than returning a failed Outcome, on three inputs it does not anticipate: a
     * step whose variable map is not JSON, a prompt whose variable list is not JSON, and a connection
     * whose API key will not decrypt (a rotated or missing LOOKUP_ENCRYPTION_KEY does that to every
     * key at once). "A throw from apply is currently impossible" is not true of today's code.
     */
    @Test
    @Tag("pinned-unreviewed")
    void applyThrowsOnAMalformedVariableMapAMalformedVariableListOrAnUndecryptableKey() {
        this.pipelineWithStep("fail", "{\"claim_id\": ");
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(null);
        assertThatThrownBy(() -> this.steps(this.runner).apply(TENANT, "F1", QUEUE_ID, STORED))
            .as("variable map").isInstanceOf(JsonSyntaxException.class);

        this.pipelineWithStep("fail", MAPPED);
        this.activePrompt(TENANT, "claim_id, document_text");
        assertThatThrownBy(() -> this.steps(this.runner).apply(TENANT, "F1", QUEUE_ID, STORED))
            .as("prompt variables").isInstanceOf(JsonSyntaxException.class);

        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(keyed(randomKey()).encrypt("sk-live-key"));
        assertThatThrownBy(() -> this.steps(this.runner).apply(TENANT, "F1", QUEUE_ID, STORED))
            .as("undecryptable key").isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Failed to decrypt lookup value: ");
        verifyNoInteractions(this.runner);
    }

    /**
     * And what the dispatcher makes of it: row 6, not row 5. A connection key encrypted under a key the
     * server no longer holds fails every attempt identically, yet the run is offered a retry and
     * reported as "could not be dispatched" -- broker trouble -- for up to max_attempts attempts.
     */
    @Test
    @Tag("pinned-unreviewed")
    void anUndecryptableConnectionKeyIsRetriedAsIfTheBrokerWereDown() throws Exception {
        this.pipelineWithStep("fail", MAPPED);
        this.activePrompt(TENANT, VARIABLES);
        this.defaultConnection(keyed(randomKey()).encrypt("sk-live-key"));

        this.push(this.engine(this.steps(this.runner)), this.dispatchableJob());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).scheduleRetry(eq(this.run), reason.capture());
        assertThat(reason.getValue()).startsWith("Job 1196 could not be dispatched: ");
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, reason.getValue());
        verifyNoInteractions(this.runner, this.kafkaTemplateProvider);
    }
}
