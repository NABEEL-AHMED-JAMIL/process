package process.pipeline;

import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.JobOwnership;
import process.security.TenantOwnership;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a run is the caller's, for the run reads and writes under sourceJob.json (the timeline, the manifest, the
 * review): its job is (JobOwnership: the workspace, and for a tenant user the jobs that name them), the job is not
 * deleted, and run and job are in one workspace. Anything else is answered as a run that does not exist.
 */
public final class RunOwnership {

    /** A run the caller may see, with its job. */
    public static final class Owned {
        public final JobQueue run;
        public final SourceJob job;

        Owned(JobQueue run, SourceJob job) {
            this.run = run;
            this.job = job;
        }
    }

    private RunOwnership() {}

    public static Optional<Owned> owned(JobQueueRepository runs, SourceJobRepository jobs, Long jobQueueId) {
        return find(runs, jobs, jobQueueId, true);
    }

    /**
     * The workspace rule alone, for a caller who acts for the whole workspace rather than as a person with jobs of their
     * own -- the customer's API client (MIG-237's customer review, MIG-234): the run's job is in the caller's workspace
     * and not deleted.
     */
    public static Optional<Owned> ownedByWorkspace(JobQueueRepository runs, SourceJobRepository jobs, Long jobQueueId) {
        return find(runs, jobs, jobQueueId, false);
    }

    private static Optional<Owned> find(JobQueueRepository runs, SourceJobRepository jobs, Long jobQueueId, boolean asPerson) {
        if (jobQueueId == null) {
            return Optional.empty();
        }
        Optional<JobQueue> run = runs.findById(jobQueueId);
        Optional<SourceJob> job = run.flatMap(r -> r.getJobId() == null ? Optional.empty() : jobs.findById(r.getJobId()));
        if (!run.isPresent() || !job.isPresent()
            || !(asPerson ? JobOwnership.isVisibleToCaller(job.get()) : TenantOwnership.isOwnedByCaller(job.get().getTenantId()))
            || Status.Delete.equals(job.get().getJobStatus()) || !Objects.equals(run.get().getTenantId(), job.get().getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(new Owned(run.get(), job.get()));
    }
}
