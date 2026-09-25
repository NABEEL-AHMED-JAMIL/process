package process.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.ai.AiStepService;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.security.RunCallbackTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MIG-201 (worker-runtime contract, section 2 and section 16 items 1 and 7): what a dispatched run's Kafka
 * record carries besides the task.
 *
 * <ul>
 *   <li>The payload names the run's workspace -- {@code tenantId}, a JSON integer, from source_job.tenant_id -- so
 *       a worker has it before it asks Core (it still verifies it with verifyRun: the dispatch is a claim, the
 *       token is the proof).</li>
 *   <li>The record is keyed by the run ({@code jobQueueId}), not a random UUID, so every redelivery and every
 *       attempt of one run lands on one partition, in order.</li>
 *   <li>The headers name the run's tenant, its pipeline and its correlation id, so a worker can route and log
 *       before it parses the value. The pipeline header is the payload's pipelineId exactly (trimmed the same
 *       way); a worker refuses a record whose header and value disagree.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DispatchRecordShapeTest {

    private static final long TENANT = 3_000_000_001L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final long USER_ID = 88L;
    private static final String PAYLOAD = "<csvCheck><inputKey>a.csv</inputKey></csvCheck>";
    private static final String CORRELATION = "run-5073-abcdef";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private AiStepService aiStepService;

    private DispatchPipeline pipeline;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.pipeline = new DispatchPipeline(this.bulkAction, this.transactionService, this.jobMail,
            this.runCallbackTokens, this.aiStepService);
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setAttempt(1);
        this.run.setCorrelationId(CORRELATION);
        this.run.setDispatchPayload(PAYLOAD);
        when(this.runCallbackTokens.issue(any(JobQueue.class))).thenReturn("cbt_1.5073.secret");
    }

    private static SourceJob job(Long tenantId, String pipelineId) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.reference&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setTaskDetailId(4200L);
        task.setPipelineId(pipelineId);
        task.setTaskPayload(PAYLOAD);
        task.setSourceTaskType(type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(tenantId);
        job.setAssignedUserId(USER_ID);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private DispatchOutbox.Record dispatch(SourceJob job) throws Exception {
        this.pipeline.dispatch(job, this.run);
        return this.pipeline.lastWritten();
    }

    @Test
    void thePayloadNamesTheRunsWorkspaceAsAWholeNumber() throws Exception {
        JsonObject sent = JsonParser.parseString(this.dispatch(job(TENANT, "REF_CSV_CHECK_V1")).payload).getAsJsonObject();

        assertThat(sent.get("tenantId").getAsJsonPrimitive().isNumber()).isTrue();
        assertThat(sent.get("tenantId").getAsLong()).isEqualTo(TENANT);
        assertThat(sent.get("tenantId").toString()).as("an integer, never a quoted or decimal number")
            .isEqualTo(String.valueOf(TENANT));
    }

    /** A job with no workspace sends none: the worker's old shape, which it still accepts, and refuses at verifyRun. */
    @Test
    void aJobWithNoWorkspaceSendsNoTenantAtAll() throws Exception {
        JsonObject sent = JsonParser.parseString(this.dispatch(job(null, "REF_CSV_CHECK_V1")).payload).getAsJsonObject();

        assertThat(sent.has("tenantId")).isFalse();
    }

    @Test
    void theRecordIsKeyedByTheRunNotAtRandom() throws Exception {
        assertThat(this.dispatch(job(TENANT, "REF_CSV_CHECK_V1")).messageKey).isEqualTo("5073");
    }

    /** A retry is the same run: same key, so it follows its first attempt onto the same partition. */
    @Test
    void everyAttemptOfARunHasTheSameKey() throws Exception {
        String first = this.dispatch(job(TENANT, "REF_CSV_CHECK_V1")).messageKey;
        this.run.setAttempt(2);
        this.run.setJobSend(false);
        String second = this.dispatch(job(TENANT, "REF_CSV_CHECK_V1")).messageKey;

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theHeadersNameTheTenantThePipelineAndTheCorrelationId() throws Exception {
        DispatchOutbox.Record record = this.dispatch(job(TENANT, "  REF_CSV_CHECK_V1 "));

        assertThat(record.headers)
            .containsEntry("x-tenant-id", String.valueOf(TENANT))
            .containsEntry("x-pipeline-id", "REF_CSV_CHECK_V1")
            .containsEntry(CorrelationId.HEADER, CORRELATION)
            .containsEntry("x-user-id", String.valueOf(USER_ID));
        JsonObject sent = JsonParser.parseString(record.payload).getAsJsonObject();
        assertThat(sent.get("pipelineId").getAsString()).as("the header is the payload's own value")
            .isEqualTo(record.headers.get("x-pipeline-id"));
    }

    @Test
    void aTaskWithNoPipelineSendsNoPipelineHeader() throws Exception {
        DispatchOutbox.Record record = this.dispatch(job(TENANT, "  "));

        assertThat(record.headers).doesNotContainKey("x-pipeline-id");
    }
}
