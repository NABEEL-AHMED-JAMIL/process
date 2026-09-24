package process.engine;

import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.security.RunCallbackTokens;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * One run through the dispatch phases, in order, on the test's thread, over the test's mocks (MIG-134,
 * MIG-136): the pre-dispatch phase prepares it (or closes it), the dispatcher writes its hand-off to the
 * outbox (captured here instead of written), and the relay's report on the broker's answer comes back to
 * the dispatcher. What the old single pushMessageToQueue did in one call, split where it is now split.
 */
final class DispatchPipeline {

    final PreDispatchPhase phase;
    final ProducerBulkEngine engine;
    final DispatchOutbox outbox;
    final List<DispatchOutbox.Record> written = new ArrayList<>();
    private final TransactionServiceImpl transactionService;

    DispatchPipeline(BulkAction bulkAction, TransactionServiceImpl transactionService, JobMail jobMail,
        RunCallbackTokens runCallbackTokens, AiStepService aiStepService) {
        this.transactionService = transactionService;
        this.outbox = mock(DispatchOutbox.class);
        lenient().doAnswer(inv -> this.written.add(inv.getArgument(0))).when(this.outbox).write(any());
        this.phase = new PreDispatchPhase(transactionService, bulkAction, aiStepService, jobMail,
            TransactionOperations.withoutTransaction(), new SameThread());
        this.engine = new ProducerBulkEngine(bulkAction, transactionService, jobMail, runCallbackTokens, this.outbox);
    }

    /** Prepares the run and, if it was prepared, dispatches it -- as the two phases would, one after the other. */
    void push(SourceJob job, JobQueue run) throws Exception {
        lenient().when(this.transactionService.markPrepared(eq(run.getJobQueueId()), any(), any(), any())).thenAnswer(inv -> {
            run.setDispatchPayload(inv.getArgument(1));
            run.setPreparedAt(inv.getArgument(2));
            return 1;
        });
        this.phase.prepare(Optional.of(job), run);
        if (run.getDispatchPayload() != null) {
            this.dispatch(job, run);
        }
    }

    /** The dispatcher's half alone, for a run already prepared. */
    void dispatch(SourceJob job, JobQueue run) throws Exception {
        Method method = ProducerBulkEngine.class.getDeclaredMethod("pushMessageToQueue", SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        try {
            method.invoke(this.engine, job, run);
        } catch (InvocationTargetException wrapper) {
            throw (Exception) wrapper.getCause();
        }
    }

    /** DispatchRelay's report that the broker took the run's message. */
    void brokerTook(JobQueue run, long offset) {
        lenient().when(this.transactionService.findJobQueueByJobQueueId(run.getJobQueueId())).thenReturn(Optional.of(run));
        this.engine.published(run.getJobQueueId(), Math.max(1, run.getAttempt()), offset);
    }

    /** DispatchRelay's report that the broker would not. */
    void brokerRefused(JobQueue run, Throwable cause) {
        lenient().when(this.transactionService.findJobQueueByJobQueueId(run.getJobQueueId())).thenReturn(Optional.of(run));
        this.engine.publishFailed(run.getJobQueueId(), Math.max(1, run.getAttempt()), cause);
    }

    DispatchOutbox.Record lastWritten() {
        return this.written.get(this.written.size() - 1);
    }

    /** Runs what it is handed on the caller's thread, so a test sees the AI lane's work finish. */
    static final class SameThread extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            this.shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
