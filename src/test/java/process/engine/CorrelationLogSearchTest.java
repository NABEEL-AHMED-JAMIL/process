package process.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import process.api.NotifyResetApi;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.JobQueueRepository;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-95's measure of success: one search on a run's correlation id finds its dispatch AND the callbacks
 * that dispatch caused -- here for a legacy worker that echoes no X-Correlation-Id, the case that used to
 * be a handful of NotifyServiceImpl lines with no thread back to anything.
 *
 * The real RunCallbackTokens stamps the id on the run in its token write; the real NotifyResetApi reads it
 * back when the callback arrives; the log lines are captured as logback records them, MDC included.
 */
@ExtendWith(MockitoExtension.class)
class CorrelationLogSearchTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private NotifyService notifyService;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger processLogger;
    private JobQueue run;

    @BeforeEach
    void capture() {
        this.processLogger = (Logger) LoggerFactory.getLogger("process");
        this.captured.start();
        this.processLogger.addAppender(this.captured);
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        lenient().when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(this.run));
        lenient().when(this.jobQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void release() {
        this.processLogger.detachAppender(this.captured);
        CorrelationId.clear();
    }

    private static SourceJob job() {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setTaskDetailId(4200L);
        task.setTaskPayload("<pipeline/>");
        task.setSourceTaskType(type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private Set<String> loggersUnder(String correlationId) {
        return this.captured.list.stream()
            .filter(event -> correlationId.equals(event.getMDCPropertyMap().get(CorrelationId.MDC_KEY)))
            .map(ILoggingEvent::getLoggerName)
            .collect(Collectors.toSet());
    }

    @Test
    void oneSearchFindsTheDispatchAndTheCallbacksOfALegacyWorker() throws Exception {
        RunCallbackTokens tokens = new RunCallbackTokens(this.jobQueueRepository, 24, "");
        DispatchOutbox outbox = mock(DispatchOutbox.class);
        ProducerBulkEngine engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail, tokens, outbox);
        // Prepared by the pre-dispatch phase; the dispatcher takes it from here.
        this.run.setPreparedAt(LocalDateTime.now());
        this.run.setDispatchPayload("<pipeline/>");
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any())).thenReturn(Collections.singletonList(this.run));
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job()));

        engine.startJobInCurrentTimeSlot();

        ArgumentCaptor<DispatchOutbox.Record> written = ArgumentCaptor.forClass(DispatchOutbox.Record.class);
        verify(outbox).write(written.capture());
        JsonObject sent = JsonParser.parseString(written.getValue().payload).getAsJsonObject();
        String correlationId = this.run.getCorrelationId();
        assertThat(CorrelationId.isAcceptable(correlationId)).isTrue();
        assertThat(sent.get("correlationId").getAsString()).as("the worker is handed the id to echo").isEqualTo(correlationId);
        assertThat(written.getValue().headers).containsEntry(CorrelationId.HEADER, correlationId);
        assertThat(CorrelationId.current()).as("cleared after the run's dispatch").isNull();

        // The worker reports back, echoing nothing: the filter bound a fresh id for the request.
        this.run.setJobStatus(JobStatus.Start);
        when(this.notifyService.changeState(any(SourceJobQueueDto.class), any())).thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "ok"));
        CorrelationId.set(CorrelationId.generate());
        SourceJobQueueDto running = new SourceJobQueueDto();
        running.setJobStatusMessage("started");
        new NotifyResetApi(this.notifyService, tokens).changeState(JOB_ID, QUEUE_ID, JobStatus.Running,
            sent.get("callbackToken").getAsString(), null, null, running);

        assertThat(this.loggersUnder(correlationId))
            .contains(ProducerBulkEngine.class.getName(), NotifyResetApi.class.getName());
        List<String> callbackLines = this.captured.list.stream()
            .filter(event -> event.getLoggerName().equals(NotifyResetApi.class.getName()))
            .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
        assertThat(callbackLines).anyMatch(line -> line.contains("run 5705") && line.contains("job 2410"));
    }
}
