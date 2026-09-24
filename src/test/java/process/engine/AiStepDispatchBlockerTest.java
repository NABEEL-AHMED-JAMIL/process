package process.engine;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import process.ai.AiProviderGateway;
import process.ai.AiStepService;
import process.ai.PromptRunner;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.engine.cron.ProcessCron;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-159, the dispatch blocker: a pipeline's server-side AI steps run inside the dispatch pass,
 * synchronously, on the scheduler's own thread. startJobInCurrentTimeSlot holds a ten-minute
 * ShedLock and gives itself a seven-minute budget, but it checks that budget only BETWEEN runs --
 * a run whose AI step is slow is never cut short -- and one AI step's own worst case is measured
 * in hours of provider timeouts, not minutes. A dispatch pass that outlives its lock lets a second
 * console claim the same queue rows. Pinned as it stands; the extracted AI service cannot keep
 * being called this way, which is why it blocks the move.
 */
@Tag("pinned-unreviewed")
class AiStepDispatchBlockerTest {

    private static final long JOB_ID = 1196L;
    private static final long JOB_QUEUE_ID = 5073L;

    private final BulkAction bulkAction = mock(BulkAction.class);
    private final TransactionServiceImpl transactionService = mock(TransactionServiceImpl.class);
    private final KafkaTemplateProvider kafkaTemplateProvider = mock(KafkaTemplateProvider.class);
    private final AiStepService aiStepService = mock(AiStepService.class);
    private final ProducerBulkEngine engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, mock(JobMail.class),
        this.kafkaTemplateProvider, mock(KafkaConnectionResolver.class), mock(RunCallbackTokens.class), this.aiStepService);

    private static SourceJob jobWithAnAiPipeline() {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L); type.setStatus(Status.Active); type.setQueueTopicPartition("topic=claims&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setSourceTaskType(type); task.setPipelineId("F1"); task.setTaskPayload("<pipeline><document>notes</document></pipeline>");
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID); job.setTenantId(2905L); job.setJobStatus(Status.Active); job.setTaskDetail(task);
        return job;
    }

    @Test
    void theAiStepsRunOnTheSchedulersThreadBeforeTheSend() {
        JobQueue run = new JobQueue();
        run.setJobQueueId(JOB_QUEUE_ID); run.setJobId(JOB_ID); run.setJobStatus(JobStatus.Queue);
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any())).thenReturn(Collections.singletonList(run));
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(jobWithAnAiPipeline()));
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        when(this.aiStepService.apply(eq(2905L), eq("F1"), eq(JOB_QUEUE_ID), any())).thenAnswer(inv -> {
            ranOn.set(Thread.currentThread());
            return new AiStepService.Outcome(inv.getArgument(3), null);
        });

        this.engine.startJobInCurrentTimeSlot();

        assertThat(ranOn.get()).as("the AI step ran synchronously inside the scheduler's call").isSameAs(Thread.currentThread());
        InOrder order = inOrder(this.aiStepService, this.kafkaTemplateProvider);
        order.verify(this.aiStepService).apply(eq(2905L), eq("F1"), eq(JOB_QUEUE_ID), any());
        order.verify(this.kafkaTemplateProvider).getTemplate(any());
    }

    /**
     * The numbers, read from the code rather than restated: a JSON step is two rounds of up to
     * ATTEMPTS calls each, and each call may wait the gateway's full read timeout. That lower bound
     * -- before the retry sleeps and the five-minute wait for a semaphore permit per round -- is
     * already several times both the dispatch budget and the lock around it.
     */
    @Test
    void oneAiStepsWorstCaseIsFarLongerThanTheDispatchBudgetAndTheLock() throws Exception {
        long budgetMs = (Long) ReflectionTestUtils.getField(ProducerBulkEngine.class, "DISPATCH_BUDGET_MS");
        String lockAtMostFor = ProcessCron.class.getMethod("startJobInCurrentTimeSlot").getAnnotation(SchedulerLock.class).lockAtMostFor();
        long lockMs = Duration.parse("PT" + lockAtMostFor).toMillis();
        int attempts = (Integer) ReflectionTestUtils.getField(PromptRunner.class, "ATTEMPTS");
        OkHttpClient http = (OkHttpClient) ReflectionTestUtils.getField(new AiProviderGateway(), "httpClient");
        long oneStepWorstCaseMs = 2L * attempts * http.readTimeoutMillis();

        assertThat(budgetMs).isEqualTo(Duration.ofMinutes(7).toMillis());
        assertThat(lockMs).isEqualTo(Duration.ofMinutes(10).toMillis());
        assertThat(oneStepWorstCaseMs).isEqualTo(Duration.ofMinutes(60).toMillis());
        assertThat(oneStepWorstCaseMs).isGreaterThan(lockMs).isGreaterThan(budgetMs);
    }
}
