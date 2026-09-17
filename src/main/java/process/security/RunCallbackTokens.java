package process.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
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

    public enum Refusal { NO_SUCH_RUN, WRONG_JOB, NOT_ISSUED, EXPIRED, MISMATCH }

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
        this.jobQueueRepository.save(jobQueue);
        return token;
    }

    /**
     * Whether a callback may act on this run. Null means yes. Every refusal is one word for the
     * log and one message for the caller; the message never says which check failed, because
     * "expired" or "wrong job" would confirm a guess.
     */
    public Optional<Refusal> verify(Long jobId, Long jobQueueId, String presented) {
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
        String token = presented == null ? "" : presented.trim();
        if (run.getCallbackTokenHash() == null) {
            // Dispatched before tokens existed: the shared secret is the only proof it can carry.
            if (!this.legacyToken.isEmpty() && constantTimeEquals(this.legacyToken, token)) {
                return Optional.empty();
            }
            return Optional.of(Refusal.NOT_ISSUED);
        }
        if (run.getCallbackTokenExpiresAt() != null && LocalDateTime.now().isAfter(run.getCallbackTokenExpiresAt())) {
            return Optional.of(Refusal.EXPIRED);
        }
        if (token.isEmpty() || !constantTimeEquals(run.getCallbackTokenHash(), sha256(token))) {
            return Optional.of(Refusal.MISMATCH);
        }
        return Optional.empty();
    }

    /** The run has ended: its token is spent, and a replayed callback finds nothing to match. */
    @Transactional
    public void retire(Long jobQueueId) {
        if (jobQueueId == null) {
            return;
        }
        this.jobQueueRepository.findById(jobQueueId).ifPresent(run -> {
            if (run.getCallbackTokenHash() == null) {
                return;
            }
            run.setCallbackTokenHash(null);
            run.setCallbackTokenExpiresAt(null);
            this.jobQueueRepository.save(run);
            logger.debug("Retired the callback token for run {}.", jobQueueId);
        });
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
