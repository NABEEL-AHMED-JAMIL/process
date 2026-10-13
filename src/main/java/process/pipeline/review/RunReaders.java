package process.pipeline.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.model.enums.ReviewParty;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinition;
import process.pipeline.RunOwnership;
import process.security.TenantContext;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * UX review 2026-10-12 (R2): who may READ a run -- its page, steps, outputs, log and review. Whoever owns it
 * ({@link RunOwnership#owned}: an administrator, or the tenant user its job names), and besides them the run's reviewers:
 * a tenant user of the run's workspace whom the pipeline names to decide its internal review (settings.review.reviewers,
 * a role or a group -- an access profile, by id), for a run whose results need that review. Before this a reviewer was
 * sent a run to decide in the Task inbox and refused the run itself, since its job does not name them.
 *
 * Reads only: retrying, running again and deciding stay the owner's and the administrators' ({@link RunOwnership#owned}),
 * and the Task inbox's for the reviewers. The page gate is unchanged in kind: the run reads open to the Queue page as well
 * (PageKey), so a workspace's viewers, who hold neither, still read no run.
 */
@Component
public class RunReaders {

    private static final Logger logger = LoggerFactory.getLogger(RunReaders.class);

    /** How long a person's group (access profile) is kept: a profile moved takes effect within it. */
    static final long GROUP_TTL_MILLIS = 60_000L;

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final RunReviews reviews;
    private final IdentityPort identity;
    private final LongSupplier clock;
    private final Map<Long, CachedGroup> groups = new ConcurrentHashMap<>();

    @Autowired
    public RunReaders(JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, IdentityPort identity) {
        this(runs, jobs, reviews, identity, System::currentTimeMillis);
    }

    RunReaders(JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, IdentityPort identity, LongSupplier clock) {
        this.runs = runs;
        this.jobs = jobs;
        this.reviews = reviews;
        this.identity = identity;
        this.clock = clock;
    }

    /** The run and its job, when the caller may read it; empty -- answered as "not found" -- otherwise. */
    public Optional<RunOwnership.Owned> readable(Long jobQueueId) {
        Optional<RunOwnership.Owned> owned = RunOwnership.owned(this.runs, this.jobs, jobQueueId);
        if (owned.isPresent()) {
            return owned;
        }
        return this.asReviewer(jobQueueId);
    }

    /** Whether the caller reads the run only as one of its reviewers (not as its owner): the answer then leaves out the job's setup. */
    public boolean readsAsReviewerOnly(Long jobQueueId) {
        return !RunOwnership.owned(this.runs, this.jobs, jobQueueId).isPresent() && this.asReviewer(jobQueueId).isPresent();
    }

    private Optional<RunOwnership.Owned> asReviewer(Long jobQueueId) {
        // Only a tenant user is ever a reviewer by this rule: an administrator owns every run of the workspace already, and
        // an API client, a platform administrator or a caller with no role is nobody's reviewer.
        if (!"TENANT_USER".equals(TenantContext.getUserRole()) || TenantContext.getAppUserId() == null
            || TenantContext.getTenantId() == null) {
            return Optional.empty();
        }
        Optional<RunOwnership.Owned> inWorkspace = RunOwnership.ownedByWorkspace(this.runs, this.jobs, jobQueueId);
        if (!inWorkspace.isPresent()) {
            return Optional.empty();
        }
        JobQueue run = inWorkspace.get().run;
        SourceJob job = inWorkspace.get().job;
        if (!this.reviews.required(run, job).contains(ReviewParty.INTERNAL)) {
            return Optional.empty();
        }
        return this.names(this.reviews.reviewersOf(run, job)) ? inWorkspace : Optional.empty();
    }

    /** Whether these reviewers name the caller: their role, or the group (access profile) they are on. */
    boolean names(PipelineDefinition.Reviewers reviewers) {
        if (reviewers == null || reviewers.getValue() == null) {
            return false;
        }
        String value = reviewers.getValue().trim();
        if (PipelineDefinition.Reviewers.ROLE.equals(reviewers.getKind())) {
            return value.equals(TenantContext.getUserRole());
        }
        if (PipelineDefinition.Reviewers.GROUP.equals(reviewers.getKind())) {
            Long group = this.groupOf(TenantContext.getAppUserId());
            return group != null && value.equals(String.valueOf(group));
        }
        return false;
    }

    /** The caller's access profile id, kept a minute; null for none, or when Identity cannot say (fails closed). */
    private Long groupOf(Long appUserId) {
        long now = this.clock.getAsLong();
        CachedGroup cached = this.groups.get(appUserId);
        if (cached != null && cached.until > now) {
            return cached.group;
        }
        Long group;
        try {
            group = this.identity.person(appUserId)
                .filter(person -> Objects.equals(person.getTenantId(), TenantContext.getTenantId()) && !person.isDeleted())
                .map(IdentityPort.Person::getPageAccessProfileId).orElse(null);
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("Run reviewer check for user {}: Identity is unavailable ({}); read as not a reviewer.", appUserId,
                unavailable.getMessage());
            return null;
        }
        if (this.groups.size() > 10_000) {
            this.groups.clear();
        }
        this.groups.put(appUserId, new CachedGroup(group, now + GROUP_TTL_MILLIS));
        return group;
    }

    private static final class CachedGroup {
        final Long group;
        final long until;

        CachedGroup(Long group, long until) {
            this.group = group;
            this.until = until;
        }
    }
}
