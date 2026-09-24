package process.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.barco.platform.correlation.CorrelationId;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;

/**
 * A callback token per run.
 *
 * Minted at dispatch, sent to the worker inside the run's message, echoed back as
 * X-Worker-Token on that run's callbacks, and checked here against a hash on the job_queue row.
 * A token is therefore good for exactly one run and attempt: nothing lives in any worker's
 * environment, a leaked message exposes one run for the length of that run, a retry replaces
 * the token, and the end of the run retires it.
 *
 * The shared WORKER_CALLBACK_TOKEN is honoured for one case only -- a run that carries no hash,
 * meaning it was dispatched before tokens existed -- and only while it is still configured.
 * Drop the variable once every worker echoes the per-run token and that path closes on its own.
 *
 * @author Nabeel Ahmed
 */
@Service
public class RunCallbackTokens {

    private static final Logger logger = LoggerFactory.getLogger(RunCallbackTokens.class);

    /** What the worker sees; the prefix and ids are for reading a log line, never trusted. */
    private static final String PREFIX = "cbt_";
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Refusal { NO_SUCH_RUN, WRONG_JOB, RUN_OVER, NOT_ISSUED, EXPIRED, MISMATCH }

    /**
     * States a run cannot leave. A run gets here by more than one road -- the worker's own
     * changeState, a cancel from the screen, the dispatcher closing a run it could not send --
     * and only the first of those passes through retire(). So the check does not rely on the
     * hash having been cleared: a run that is over is refused whatever its row still carries.
     */
    private static final EnumSet<JobStatus> OVER = EnumSet.of(
        JobStatus.Failed, JobStatus.Completed, JobStatus.Skip, JobStatus.Interrupt, JobStatus.Missed);

    /** Whether a run has ended, by the same rule the callback check applies (MIG-189). */
    public static boolean isOver(JobStatus status) {
        return status != null && OVER.contains(status);
    }

    private final JobQueueRepository jobQueueRepository;
    private final long budgetHours;
    private final String legacyToken;

    public RunCallbackTokens(JobQueueRepository jobQueueRepository,
        /*
         * How long a token stays good after dispatch, whatever the run does. A backstop for a
         * run that never reports, not a run timeout: a day is long enough for any honest run
         * and short enough that a leaked token is not a standing credential.
         */
        @Value("${worker.callback.budget-hours:24}") long budgetHours,
        /* The shared secret every worker used to carry; empty once no worker does. */
        @Value("${worker.callback.token:}") String legacyToken) {
        this.jobQueueRepository = jobQueueRepository;
        this.budgetHours = budgetHours;
        this.legacyToken = legacyToken == null ? "" : legacyToken.trim();
    }

    /**
     * Mints the token for this dispatch, stores its hash on the row, and returns the token for
     * the message. Saved before the caller sends, so a fast worker cannot call back before the
     * server knows the token; re-minting on a retry overwrites the previous attempt's hash.
     */
    @Transactional
    public String issue(JobQueue jobQueue) {
        byte[] random = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(random);
        String token = PREFIX + Math.max(1, jobQueue.getAttempt()) + "." + jobQueue.getJobQueueId() + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jobQueue.setCallbackTokenHash(sha256(token));
        jobQueue.setCallbackTokenAttempt(Math.max(1, jobQueue.getAttempt()));
        jobQueue.setCallbackTokenExpiresAt(LocalDateTime.now().plusHours(this.budgetHours));
        // In the same write as the hash (MIG-95): the dispatch's id if one is bound, otherwise a new
        // one, and a retry keeps the id its first dispatch was given -- it is the same piece of work.
        if (jobQueue.getCorrelationId() == null) {
            String current = CorrelationId.current();
            jobQueue.setCorrelationId(CorrelationId.isAcceptable(current) ? current : CorrelationId.generate());
        }
        this.jobQueueRepository.save(jobQueue);
        return token;
    }

    /**
     * Whether a callback may act on this run. Null means yes. Every refusal is one word for the
     * log and one message for the caller; the message never says which check failed, because
     * "expired" or "wrong job" would confirm a guess.
     */
    public Optional<Refusal> verify(Long jobId, Long jobQueueId, String presented) {
        return this.verify(jobId, jobQueueId, presented, true);
    }

    /**
     * The same proof, for a usage report rather than a callback. A run that is over may still
     * report what it used -- the worker sends its batch as the run closes, and a spooled batch
     * from a meter outage arrives with the next run -- so RUN_OVER is not a refusal here; the
     * token's own expiry still is, which bounds how late a report can be. Nothing a report can
     * do changes the run, so the replay concern that makes RUN_OVER a refusal for callbacks
     * does not apply: the meter deduplicates by key.
     */
    public Optional<Refusal> verifyForReport(Long jobId, Long jobQueueId, String presented) {
        return this.verify(jobId, jobQueueId, presented, false);
    }

    private Optional<Refusal> verify(Long jobId, Long jobQueueId, String presented, boolean refuseWhenOver) {
        if (jobQueueId == null) {
            return Optional.of(Refusal.NO_SUCH_RUN);
        }
        Optional<JobQueue> found = this.jobQueueRepository.findById(jobQueueId);
        if (!found.isPresent()) {
            return Optional.of(Refusal.NO_SUCH_RUN);
        }
        JobQueue run = found.get();
        if (jobId == null || !jobId.equals(run.getJobId())) {
            return Optional.of(Refusal.WRONG_JOB);
        }
        // The token first, then what the run's state says about it. RUN_OVER and EXPIRED are thereby
        // only ever the verdict on the run's OWN token: a caller who merely knows the ids is told
        // MISMATCH or NOT_ISSUED whatever the run is doing. The caller still sees one uniform 401; the
        // order matters to what the server may do on the strength of a refusal -- answer a finished
        // run's redelivered callback from its receipt (MIG-18), or take a genuine but expired report
        // as evidence the worker is done (MIG-63).
        String token = presented == null ? "" : presented.trim();
        if (run.getCallbackTokenHash() == null) {
            // Dispatched before tokens existed: the shared secret is the only proof it can carry.
            if (this.legacyToken.isEmpty() || !constantTimeEquals(this.legacyToken, token)) {
                return Optional.of(Refusal.NOT_ISSUED);
            }
        } else {
            if (token.isEmpty() || !constantTimeEquals(run.getCallbackTokenHash(), sha256(token))) {
                return Optional.of(Refusal.MISMATCH);
            }
            if (run.getCallbackTokenExpiresAt() != null && LocalDateTime.now().isAfter(run.getCallbackTokenExpiresAt())) {
                return Optional.of(Refusal.EXPIRED);
            }
        }
        if (refuseWhenOver && run.getJobStatus() != null && OVER.contains(run.getJobStatus())) {
            return Optional.of(Refusal.RUN_OVER);
        }
        return Optional.empty();
    }

    /** How long after a run ends its token may still vouch for a usage report. */
    static final long REPORT_GRACE_HOURS = 24;

    /**
     * The run has ended: its token is spent for callbacks -- the OVER check above refuses them
     * whatever the row carries -- but stays good for a usage report for a day. The worker
     * reports as the run closes, and a batch spooled through a meter outage arrives with the
     * next run; both must still be provably this run's. Clearing the hash here used to make
     * every late report NOT_ISSUED, which is a lost line on the bill, not a defence.
     */
    @Transactional
    public void retire(Long jobQueueId) {
        if (jobQueueId == null) {
            return;
        }
        this.jobQueueRepository.findById(jobQueueId).ifPresent(run -> {
            if (run.getCallbackTokenHash() == null) {
                return;
            }
            run.setCallbackTokenExpiresAt(LocalDateTime.now().plusHours(REPORT_GRACE_HOURS));
            this.jobQueueRepository.save(run);
            logger.debug("Retired the callback token for run {}; good for reports until {}.", jobQueueId, run.getCallbackTokenExpiresAt());
        });
    }

    /**
     * The id a run's dispatch was logged under, and its tenant, for a callback that echoed no id of its
     * own (MIG-95). Read only once the callback's token has been found to be the run's own, so a caller
     * who merely knows a run id learns nothing from the answer's X-Correlation-Id.
     */
    @Transactional(readOnly = true)
    public Optional<RunCorrelation> correlationOf(Long jobQueueId) {
        if (jobQueueId == null) {
            return Optional.empty();
        }
        return this.jobQueueRepository.findById(jobQueueId).map(run -> new RunCorrelation(run.getCorrelationId(),
            run.getSourceJob() == null ? null : run.getSourceJob().getTenantId()));
    }

    /** What a callback is logged under: its dispatch's correlation id, and the run's tenant. */
    public static final class RunCorrelation {

        public final String correlationId;
        public final Long tenantId;

        public RunCorrelation(String correlationId, Long tenantId) {
            this.correlationId = correlationId;
            this.tenantId = tenantId;
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
