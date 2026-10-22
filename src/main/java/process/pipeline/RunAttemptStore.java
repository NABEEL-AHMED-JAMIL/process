package process.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MIG-345: the attempts of a run that someone asked for (run_attempt, V205) -- a resume from the failed step or a restart,
 * by a person in the console or an API client. An attempt the job's own retry policy made has none.
 */
public interface RunAttemptStore {

    String RESUME = "resume";
    String RESTART = "restart";
    String VIA_CONSOLE = "console";
    String VIA_API = "api";
    /** Owner 2026-10-21 (V222): Core itself, resuming a run its restart stopped; no person or client asked. */
    String VIA_PLATFORM = "platform";

    /** Records an attempt someone asked for, in the caller's transaction. */
    void record(Attempt attempt);

    /** The attempt of this number of the run, if someone asked for it. */
    Optional<Attempt> find(long jobQueueId, int attempt);

    /** Every attempt of the run someone asked for, oldest first. */
    List<Attempt> ofRun(long jobQueueId);

    /** One run_attempt row. */
    final class Attempt {
        public long jobQueueId;
        public int attempt;
        public int previousAttempt;
        /** {@link #RESUME} or {@link #RESTART}. */
        public String mode;
        /** The index of the first step that runs (resume); null for a restart. */
        public Integer fromStepIndex;
        public String fromStepKey;
        public int reusedSteps;
        /** The definition version the attempt runs. */
        public Long pipelineDefinitionId;
        /** {@link #VIA_CONSOLE} or {@link #VIA_API}. */
        public String requestedVia;
        public Long requestedByUserId;
        public String requestedByClientId;
        public Instant requestedAt;

        public boolean isResume() {
            return RESUME.equals(this.mode);
        }
    }
}
