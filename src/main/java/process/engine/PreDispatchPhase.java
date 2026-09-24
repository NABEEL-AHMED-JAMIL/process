package process.engine;

import process.util.BusinessTime;
import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.ai.AiStepService;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.util.exception.ExceptionUtil;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pre-dispatch phase: between the enqueuer and the dispatcher, it decides whether each queued run
 * can be sent at all and answers its server AI steps into the document to send (MIG-134, MIG-25).
 *
 * AI steps used to run on the dispatcher's own thread, inside its seven-minute budget and its ten-minute
 * lock, so one slow model call held up every other queued job and could outlive the lock. They run here
 * instead, on their own threads: a run with AI steps is prepared on one of a few AI threads, a run with
 * none on the pass's thread, and the dispatcher only ever takes a run this phase has finished with. A
 * model call that stalls holds its own run, and nothing else.
 *
 * <b>Every run this phase takes leaves it prepared or closed.</b> {@link #prepare} is the only way out of
 * a claim: it writes the prepared document, or it closes the run through {@link DispatchFailures} --
 * configuration faults and failed AI steps as final failures, anything thrown as a transient one that
 * the job's retry policy may try again -- and a Throwable escaping the decision is caught and closed the
 * same way. A run that is neither dispatched nor closed permanently disables its job, because the
 * dispatcher counts Queue, Start and Running to decide the job is busy; a row left in Queue while AI runs
 * asynchronously is exactly that failure, so there is no path that returns without one or the other.
 * The only exception is the process dying mid-call: the claim's lease then runs out and the run is taken
 * again, and the AI service hands back any answer it already recorded for it.
 *
 * Replicas share the phase with no coordinator: runs are claimed FOR UPDATE SKIP LOCKED and leased
 * (DispatchTiming.PREPARE_LEASE), so no two preparers take one run.
 */
@Component
public class PreDispatchPhase {

    private static final Logger logger = LoggerFactory.getLogger(PreDispatchPhase.class);

    /** What the phase decided for one run. */
    static final class Decision {

        final String payload;
        final String refusal;
        final boolean retryable;
        final List<String> notes;

        private Decision(String payload, String refusal, boolean retryable, List<String> notes) {
            this.payload = payload;
            this.refusal = refusal;
            this.retryable = retryable;
            this.notes = notes;
        }

        static Decision prepared(String payload, List<String> notes) {
            return new Decision(payload, null, false, notes);
        }

        static Decision refused(String refusal, boolean retryable, List<String> notes) {
            return new Decision(null, refusal, retryable, notes);
        }

        boolean isPrepared() {
            return this.refusal == null;
        }
    }

    private final TransactionServiceImpl transactionService;
    private final BulkAction bulkAction;
    private final AiStepService aiStepService;
    private final DispatchFailures failures;
    private final TransactionOperations transactions;
    private final ExecutorService aiThreads;
    /** Runs this instance is preparing, so a pass never hands one to a second thread. */
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    @Autowired
    public PreDispatchPhase(TransactionServiceImpl transactionService, BulkAction bulkAction, AiStepService aiStepService,
        JobMail jobMail, PlatformTransactionManager transactionManager) {
        this(transactionService, bulkAction, aiStepService, jobMail, new TransactionTemplate(transactionManager),
            newAiThreads());
    }

    PreDispatchPhase(TransactionServiceImpl transactionService, BulkAction bulkAction, AiStepService aiStepService,
        JobMail jobMail, TransactionOperations transactions, ExecutorService aiThreads) {
        this.transactionService = transactionService;
        this.bulkAction = bulkAction;
        this.aiStepService = aiStepService;
        this.failures = new DispatchFailures(bulkAction, transactionService, jobMail, transactions);
        this.transactions = transactions;
        this.aiThreads = aiThreads;
    }

    private static ExecutorService newAiThreads() {
        AtomicInteger count = new AtomicInteger();
        return Executors.newFixedThreadPool(DispatchTiming.PREPARE_AI_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "pre-dispatch-ai-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() {
        this.aiThreads.shutdownNow();
    }

    /**
     * One pass: claims the runs waiting to be prepared, prepares those without AI steps here and hands
     * the rest to the AI threads. Returns how many it took.
     */
    public int runPass() {
        LocalDateTime now = BusinessTime.now();
        List<Long> claimed;
        try {
            claimed = this.transactions.execute(status -> this.transactionService.claimRunsToPrepare(now,
                DispatchTiming.PREPARE_BATCH, now.plus(DispatchTiming.PREPARE_LEASE)));
        } catch (RuntimeException ex) {
            logger.error("Pre-dispatch could not claim runs: {}.", ExceptionUtil.getRootCauseMessage(ex));
            return 0;
        }
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        List<Long> taken = new ArrayList<>(claimed);
        for (Long jobQueueId : taken) {
            if (!this.inProgress.add(jobQueueId)) {
                continue;
            }
            try {
                Optional<JobQueue> run = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
                if (!run.isPresent()) {
                    this.inProgress.remove(jobQueueId);
                    continue;
                }
                Optional<SourceJob> job = this.transactionService.findByJobIdAndJobStatus(run.get().getJobId(), Status.Active);
                if (job.isPresent() && job.get().getTaskDetail() != null
                    && this.aiStepService.hasSteps(job.get().getTenantId(), job.get().getTaskDetail().getPipelineId())) {
                    this.aiThreads.execute(() -> this.prepareAndRelease(job, run.get()));
                } else {
                    this.prepareAndRelease(job, run.get());
                }
            } catch (RuntimeException ex) {
                this.inProgress.remove(jobQueueId);
                logger.error("Pre-dispatch could not take run {}: {}.", jobQueueId, ExceptionUtil.getRootCauseMessage(ex));
            }
        }
        return taken.size();
    }

    private void prepareAndRelease(Optional<SourceJob> job, JobQueue run) {
        try {
            this.prepare(job, run);
        } finally {
            this.inProgress.remove(run.getJobQueueId());
        }
    }

    /**
     * Prepares one run or closes it -- never neither. The job must be active; its task must route
     * somewhere (DispatchRoute, rows 1 to 4); its AI steps must answer or be allowed to fail (row 5).
     * Anything thrown is a transient failure (row 6): the run is closed as retryable.
     */
    void prepare(Optional<SourceJob> job, JobQueue run) {
        if (run.getCorrelationId() == null) {
            run.setCorrelationId(CorrelationId.generate());
        }
        CorrelationId.set(run.getCorrelationId());
        try {
            Decision decision;
            try {
                decision = this.decide(job, run);
            } catch (Throwable ex) {
                decision = Decision.refused(String.format("Job %s could not be dispatched: %s", run.getJobId(),
                    DispatchFailures.reasonFor(ex)), true, Collections.emptyList());
                logger.error("Pre-dispatch of run {} threw: {}.", run.getJobQueueId(), ExceptionUtil.getRootCauseMessage(ex));
            }
            this.finish(run, decision);
        } catch (RuntimeException ex) {
            // Writing the outcome itself failed: the lease runs out and the run is taken again.
            logger.error("Pre-dispatch could not record the outcome for run {}: {}.", run.getJobQueueId(),
                ExceptionUtil.getRootCauseMessage(ex));
        } finally {
            CorrelationId.clear();
        }
    }

    Decision decide(Optional<SourceJob> job, JobQueue run) {
        if (!job.isPresent()) {
            return Decision.refused(String.format(
                "Job %s failed in the queue because the main job is deleted or inactive.", run.getJobId()), false,
                Collections.emptyList());
        }
        DispatchRoute route = DispatchRoute.of(job.get(), run.getJobId());
        if (route.refused()) {
            return Decision.refused(route.refusal, false, Collections.emptyList());
        }
        // The pipeline's AI steps write their answers into the task's document; the worker then sees
        // ordinary tags. A step that fails (and says the run must) closes the run without a send.
        AiStepService.Outcome steps = this.aiStepService.apply(job.get().getTenantId(),
            job.get().getTaskDetail().getPipelineId(), run.getJobQueueId(), job.get().getTaskDetail().getTaskPayload());
        if (steps.failed()) {
            return Decision.refused(String.format("Job %s: %s", run.getJobId(), steps.failure), false, steps.notes);
        }
        return Decision.prepared(steps.payload, steps.notes);
    }

    /** The notes and the verdict, in one local transaction. */
    private void finish(JobQueue run, Decision decision) {
        this.transactions.execute(status -> {
            // The narrative first, then the verdict: a failed step's notes are in the history before the Failed.
            for (String note : decision.notes) {
                this.bulkAction.saveJobAuditLogs(run.getJobQueueId(), note);
            }
            if (decision.isPrepared()) {
                int prepared = this.transactionService.markPrepared(run.getJobQueueId(), decision.payload,
                    BusinessTime.now(), run.getCorrelationId());
                if (prepared == 0) {
                    logger.info("Run {} was closed or dispatched while it was being prepared; left as it is.", run.getJobQueueId());
                }
            } else {
                this.failures.close(run, decision.refusal, decision.retryable);
            }
            return null;
        });
    }
}
