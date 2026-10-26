package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.slo.RunSlo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-424: a pipeline's Run pipeline step calls another pipeline (a job, a schedule) of the same workspace and waits for its
 * answer. The called run is a normal run -- queued, logged, billed, on its pipeline's runs -- that says which run called it
 * and how deep the chain is (V235: job_queue.called_by_run_id, call_depth). It runs beside the job's own runs (one in flight
 * per job and calling run) and on the calling step's own thread: the step waits for it without taking a second run thread.
 *
 * <ul>
 *   <li>The called job is the workspace's own, active, and runs Core's steps; another workspace's job is "no such
 *   pipeline", as one that does not exist.</li>
 *   <li>Its parameters are checked as Run with... checks them (a name it does not declare, a wrong type, a required one
 *   missing: the refusal, in words).</li>
 *   <li>A chain is at most {@value #MAX_DEPTH} calls deep, and never calls a job already in it (A calls B calls A).</li>
 *   <li>A paused workspace calls nothing.</li>
 * </ul>
 */
@Component
public class PipelineCalls {

    public static final int MAX_DEPTH = 3;

    private static final Logger logger = LoggerFactory.getLogger(PipelineCalls.class);
    /** How often a call whose run another thread took (or that waits for an approval) looks whether it has ended. */
    static volatile long pollMillis = 1000;

    private final TransactionServiceImpl jobs;
    private final ProducerBulkEngine producer;
    private final StepStore steps;
    private final PipelineParameters parameters;
    private final RunResults results;
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final ObjectProvider<StepEngine> engine;

    public PipelineCalls(TransactionServiceImpl jobs, ProducerBulkEngine producer, StepStore steps, PipelineParameters parameters,
        RunResults results, JdbcTemplate sql, PlatformTransactionManager transactionManager, ObjectProvider<StepEngine> engine) {
        this.jobs = jobs;
        this.producer = producer;
        this.steps = steps;
        this.parameters = parameters;
        this.results = results;
        this.sql = sql;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.engine = engine;
    }

    /** A call refused before any run was made: the step's error, in words. */
    public static final class Refused extends IllegalStateException {
        public Refused(String message) {
            super(message);
        }
    }

    /** How a called run ended: its id, status and status line, and the rows it answered (when it completed). */
    public static final class Called {
        public long runId;
        public String status;
        public String message;
        public Dataset rows = Dataset.EMPTY;
        public RunResults.Result about = new RunResults.Result();

        public boolean ok() {
            return "Completed".equalsIgnoreCase(this.status);
        }
    }

    /** One link of a call chain: a run, its job, the run that called it. */
    static final class Link {
        long runId;
        long jobId;
        Long calledBy;
        Integer depth;
    }

    /**
     * Calls job {@code jobId} for run {@code callerRunId} of workspace {@code tenantId}, with these parameter values (null or
     * empty: its defaults), and answers how it ended and the rows {@code selection} takes from it (at most {@code maxRows}).
     * A refusal before the call ({@link Refused}) makes no run.
     */
    public Called call(long tenantId, long callerRunId, long jobId, Map<String, ?> given, RunResults.Selection selection, int maxRows)
        throws Exception {
        List<Link> chain = this.chain(callerRunId);
        if (chain.isEmpty()) {
            throw new Refused("The calling run " + callerRunId + " is not there.");
        }
        Link caller = chain.get(0);
        int depth = (caller.depth == null ? 0 : caller.depth) + 1;
        if (depth > MAX_DEPTH) {
            throw new Refused(String.format("Run pipeline calls at most %d pipelines deep; this run is already %d deep.", MAX_DEPTH,
                depth - 1));
        }
        for (Link link : chain) {
            if (link.jobId == jobId) {
                throw new Refused(String.format("Pipeline %d is already in this call chain (runs %s): a pipeline never calls itself, "
                    + "directly or through another.", jobId, describe(chain)));
            }
        }
        Optional<SourceJob> found = this.jobs.findByJobId(jobId).filter(j -> j.getTenantId() != null && j.getTenantId() == tenantId
            && j.getJobStatus() != Status.Delete);
        if (!found.isPresent()) {
            throw new Refused("No pipeline " + jobId + " in this workspace.");
        }
        SourceJob job = found.get();
        if (job.getJobStatus() != Status.Active) {
            throw new Refused("Pipeline " + jobId + " (" + job.getJobName() + ") is not active.");
        }
        if (!this.parameters.runsSteps(job)) {
            throw new Refused("Pipeline " + jobId + " (" + job.getJobName() + ") does not run on Core's step engine, so it cannot be called.");
        }
        Optional<String> paused = this.producer.workspacePause(tenantId);
        if (paused.isPresent()) {
            throw new Refused("This workspace is " + paused.get() + ", so its runs are paused.");
        }
        PipelineParameters.Checked checked = this.parameters.check(job, given);
        if (checked.refusal != null) {
            throw new Refused(checked.refusal);
        }
        JobQueue run;
        boolean[] claimed = {false};
        try {
            run = this.transactions.execute(status -> {
                JobQueue queued = this.producer.addCalledJobInQueue(job, callerRunId, depth, checked.runParams);
                claimed[0] = this.steps.claimForEngine(queued.getJobQueueId(), queued.getCorrelationId());
                return queued;
            });
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isCallerViolation(failed)) {
                throw new Refused("A run of pipeline " + jobId + " this run called is still in flight.");
            }
            throw failed;
        }
        logger.info("Run {} calls pipeline {} as run {} (depth {}).", callerRunId, jobId, run.getJobQueueId(), depth);
        if (claimed[0]) {
            this.engine.getObject().runCalled(job, run);
        } else {
            // Never expected (the row was this transaction's own): the pre-dispatch pass runs it, and this step waits for it.
            logger.warn("Called run {} could not be claimed for the step engine at once; it waits for the pass.", run.getJobQueueId());
        }
        Called called = new Called();
        called.runId = run.getJobQueueId();
        JobQueue now = this.waitForEnd(run.getJobQueueId());
        called.status = now.getJobStatus() == null ? null : now.getJobStatus().name();
        called.message = now.getJobStatusMessage();
        if (called.ok()) {
            called.rows = this.results.rows(called.runId, selection, maxRows, called.about);
        }
        return called;
    }

    /**
     * The run as it ended. A run that has not (it waits at an Approval step, or another thread took it) is looked at every
     * {@link #pollMillis} until it ends: the step's own timeout bounds the wait, by interrupting this thread.
     */
    JobQueue waitForEnd(long runId) throws InterruptedException {
        while (true) {
            Optional<JobQueue> run = this.jobs.findJobQueueByJobQueueId(runId);
            if (!run.isPresent()) {
                throw new IllegalStateException("The called run " + runId + " is no longer there.");
            }
            if (RunSlo.isTerminal(run.get().getJobStatus())) {
                return run.get();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Stopped waiting for called run " + runId + ".");
            }
            Thread.sleep(pollMillis);
        }
    }

    /** The calling run and the runs above it, nearest first (at most {@value #MAX_DEPTH} + 1). */
    List<Link> chain(long runId) {
        List<Link> chain = new ArrayList<>();
        Long next = runId;
        while (next != null && chain.size() <= MAX_DEPTH + 1) {
            List<Link> found = this.sql.query("SELECT job_queue_id, job_id, called_by_run_id, call_depth FROM job_queue WHERE job_queue_id = ?",
                (rs, n) -> {
                    Link link = new Link();
                    link.runId = rs.getLong("job_queue_id");
                    link.jobId = rs.getLong("job_id");
                    link.calledBy = (Long) rs.getObject("called_by_run_id");
                    Object depth = rs.getObject("call_depth");
                    link.depth = depth == null ? null : ((Number) depth).intValue();
                    return link;
                }, next);
            if (found.isEmpty()) {
                break;
            }
            chain.add(found.get(0));
            next = found.get(0).calledBy;
        }
        return chain;
    }

    private static String describe(List<Link> chain) {
        StringBuilder text = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            text.append(chain.get(i).runId).append(" (pipeline ").append(chain.get(i).jobId).append(')');
            if (i > 0) {
                text.append(" > ");
            }
        }
        return text.toString();
    }
}
