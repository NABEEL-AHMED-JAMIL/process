package process.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import process.model.pojo.SourceJob;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.TestNotifications;
import process.security.RunCallbackTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-94 on the dispatch path: each run is prepared and dispatched under its own id, and the tick that took it
 * gets its own id back afterwards (X5). The old code cleared the id after each run, so the rest of a tick --
 * the next run's lookup, the tick's own summary line -- was logged under nothing.
 *
 * And a run made by a request (Run now, Skip next) takes that request's id, so the id the console was answered
 * with is the id the dispatch, the worker and every callback are logged under (X9's first hop). A run the
 * scheduler makes is left for dispatch to name, one id per run.
 */
@ExtendWith(MockitoExtension.class)
class DispatchCorrelationTest {

    private static final String TICK = "01J8ZTICK0000000000000000A";
    private static final String RUN = "01J8ZRUN00000000000000000B";
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private AiStepService aiStepService;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private TestNotifications.LegacySink legacy;
    @Mock private TestNotifications.NoticeSink notices;
    @Mock private TestNotifications.FeedSink feed;

    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setCorrelationId(RUN);
        CorrelationId.set(TICK);
    }

    @AfterEach
    void tidy() {
        CorrelationId.clear();
    }

    @Test
    void aRunIsPreparedUnderItsOwnIdAndTheTickGetsItsIdBack() {
        PreDispatchPhase phase = new PreDispatchPhase(this.transactionService, this.bulkAction, this.aiStepService,
            this.jobMail, TransactionOperations.withoutTransaction(), new DispatchPipeline.SameThread());
        AtomicReference<String> during = new AtomicReference<>();
        lenient().when(this.bulkAction.scheduleRetry(any(JobQueue.class), any())).thenAnswer(call -> {
            during.set(CorrelationId.current());
            return true;
        });
        lenient().doAnswer(call -> {
            during.set(CorrelationId.current());
            return null;
        }).when(this.bulkAction).changeJobQueueStatus(any(), any(), any());

        phase.prepare(Optional.empty(), this.run);

        assertThat(during.get()).as("the run's own id while it is closed").isEqualTo(RUN);
        assertThat(CorrelationId.current()).as("the tick's id afterwards").isEqualTo(TICK);
    }

    @Test
    void aRunIsDispatchedUnderItsOwnIdAndTheTickGetsItsIdBack() {
        ProducerBulkEngine engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.runCallbackTokens, null);
        engine.useClock(() -> 0L, pause -> { });
        when(this.transactionService.findOrchestrationSetting(any())).thenReturn("10");
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any())).thenReturn(Collections.singletonList(this.run));
        AtomicReference<String> during = new AtomicReference<>();
        when(this.transactionService.findByJobIdAndJobStatus(any(), any())).thenAnswer(call -> {
            during.set(CorrelationId.current());
            return Optional.empty();
        });

        engine.startJobInCurrentTimeSlot();

        assertThat(during.get()).isEqualTo(RUN);
        assertThat(CorrelationId.current()).isEqualTo(TICK);
    }

    @Test
    void runNowMakesTheRunUnderTheRequestsId() {
        CorrelationId.set("console-7f3a9c21");
        BulkAction real = new BulkAction(this.transactionService,
            TestNotifications.recording(this.feed, this.legacy, this.notices, null));

        real.createJobQueueV1(JOB_ID, LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);

        ArgumentCaptor<JobQueue> saved = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.transactionService).saveOrUpdateJobQueue(saved.capture());
        assertThat(saved.getValue().getCorrelationId()).isEqualTo("console-7f3a9c21");
    }

    @Test
    void aScheduledRunIsLeftForDispatchToName() {
        BulkAction real = new BulkAction(this.transactionService,
            TestNotifications.recording(this.feed, this.legacy, this.notices, null));

        real.createJobQueue(JOB_ID, LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);

        ArgumentCaptor<JobQueue> saved = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.transactionService).saveOrUpdateJobQueue(saved.capture());
        assertThat(saved.getValue().getCorrelationId()).as("one id per run, minted at dispatch").isNull();
    }

    /** X9's enqueue hop: "Run now" says which run it made, under the request's id. */
    @Test
    void runNowNamesTheRunItMadeUnderTheRequestsId() {
        CorrelationId.set("console-7f3a9c21");
        ProducerBulkEngine engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.runCallbackTokens, null);
        JobQueue made = new JobQueue();
        made.setJobQueueId(7401L);
        when(this.bulkAction.createJobQueueV1(any(), any(), any(), any(), any())).thenReturn(made);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        Logger logger = (Logger) LoggerFactory.getLogger(ProducerBulkEngine.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            engine.addManualJobInQueue(job);
        } finally {
            logger.detachAppender(lines);
        }

        assertThat(lines.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Run 7401 of job 1196 queued by request.");
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "console-7f3a9c21");
        });
    }
}
