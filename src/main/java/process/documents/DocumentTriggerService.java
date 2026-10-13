package process.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.DocumentDatasets;
import process.security.JobOwnership;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Core's consumer of Document Intelligence's approvals (MIG-364): a job may be started when a document of a type is
 * approved -- "When a document of type X is approved", beside the inbox and the schedule.
 *
 * <p>An approval goes to every job of its workspace whose enabled trigger names the document's type, each ONCE per
 * document and version: document_approval is unique on (extraction, type version, job), so a redelivered event finds its
 * outcome recorded and does nothing. As the inbox (MIG-360), an approval is never dropped: it is recorded Waiting, and the
 * job's next run takes it at once when the job is free, or when the run in flight ends ({@link #startNext}: the oldest
 * waiting documents, as many as the trigger's batch size -- 1, a run per document). The queue sweep
 * ({@link DocumentQueueSweep}) starts the next run of every job with waiting documents and nothing in flight. Run now's
 * rules hold:
 * <ul>
 *   <li>a job that is gone or not Active does not run: the document is recorded Skipped with the reason (and documents
 *       that waited for a job switched off since are Skipped then, saying so);</li>
 *   <li>a Suspended or Inactive workspace's jobs are paused (owner decision 2026-09-24): its documents wait, and start once
 *       the workspace is active again.</li>
 * </ul>
 * The listener has no signed-in caller: all of this runs as the event's workspace (RowSecurity.forTenant), so another
 * workspace's triggers and jobs are not even visible to it -- one workspace's approval never starts another's job.
 *
 * <p>The run carries its documents by reference (the approvals that name it): its read_documents step with no type reads
 * them, and {{document.<field>}} fills from the first, both through ai-service's trusted read as the run's workspace --
 * the values are never copied into Core.
 *
 * <p>The console's side -- a job's trigger and its approvals -- follows JobOwnership: whoever may see the job may set its
 * trigger; anything else reads as not found. The type must be one the workspace may read (asked of ai-service).
 */
@Service
public class DocumentTriggerService {

    static final String JOB_NOT_FOUND = "SourceJob not found with jobId.";
    static final int DEFAULT_APPROVALS = 50;
    static final int MAX_APPROVALS = 200;
    static final String WAITS = "A run of this job was in flight when this document was approved; it waits, and the job's next run takes it.";
    static final String GONE = "The job no longer exists.";

    /** What one approval did to one job. */
    public enum Outcome { STARTED, WAITING, SKIPPED, ALREADY_RECORDED }

    private static final Logger logger = LoggerFactory.getLogger(DocumentTriggerService.class);

    private final DocumentTriggerStore store;
    private final ProducerBulkEngine engine;
    private final TransactionServiceImpl jobs;
    private final TransactionTemplate transactions;
    private DocumentDatasets documents;

    public DocumentTriggerService(DocumentTriggerStore store, ProducerBulkEngine engine, TransactionServiceImpl jobs,
        PlatformTransactionManager transactionManager) {
        this.store = store;
        this.engine = engine;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Where a trigger's type is checked to be the workspace's; without it, any type id is taken as given. */
    @Autowired(required = false)
    public void useDocuments(DocumentDatasets documents) {
        this.documents = documents;
    }

    // ---- an approval ---------------------------------------------------------------------------------------------------

    /** Hands the approval to every job its workspace set to start on the type, once. Run as that workspace (RowSecurity.forTenant). */
    public List<Outcome> onApproval(DocumentApproved approved) {
        List<Outcome> outcomes = new ArrayList<>();
        for (DocumentTriggerStore.Trigger trigger : this.store.enabledFor(approved.getTenantId(), approved.getDocumentTypeId())) {
            if (trigger.tenantId != approved.getTenantId() || trigger.documentTypeId != approved.getDocumentTypeId()) {
                continue;
            }
            outcomes.add(this.accept(approved, trigger.jobId));
        }
        return outcomes;
    }

    private Outcome accept(DocumentApproved approved, long jobId) {
        if (this.store.recorded(approved.getExtractionId(), approved.getTypeVersion(), jobId)) {
            logger.info("Approved {} was already handled for job {}; nothing more to do.", approved, jobId);
            return Outcome.ALREADY_RECORDED;
        }
        Optional<SourceJob> job = this.jobs.findByJobId(jobId);
        String refusal = refusal(job, approved.getTenantId());
        if (refusal != null) {
            this.transactions.executeWithoutResult(status -> this.store.record(approved, jobId, DocumentTriggerStore.SKIPPED, refusal));
            logger.info("Approved {} did not start job {}: {}", approved, jobId, refusal);
            return Outcome.SKIPPED;
        }
        Boolean recorded = this.transactions.execute(status -> this.store.record(approved, jobId, DocumentTriggerStore.WAITING, WAITS));
        if (!Boolean.TRUE.equals(recorded)) {
            return Outcome.ALREADY_RECORDED;
        }
        Started next = this.startNext(jobId);
        if (next.extractionIds.contains(approved.getExtractionId())) {
            return Outcome.STARTED;
        }
        logger.info("Approved {} waits for job {}'s next run ({} document(s) waiting; {}).", approved, jobId, this.store.waitingCount(jobId),
            next);
        return Outcome.WAITING;
    }

    /** What {@link #startNext} did: the run it made (null for none) and the documents it took. */
    public static final class Started {
        static final Started NONE = new Started(null, Collections.emptyList(), null);

        public final Long jobQueueId;
        public final List<Long> extractionIds;
        /** Why nothing started, for the log: in flight, paused, nothing waiting. */
        public final String why;

        Started(Long jobQueueId, List<Long> extractionIds, String why) {
            this.jobQueueId = jobQueueId;
            this.extractionIds = extractionIds;
            this.why = why;
        }

        @Override
        public String toString() {
            return this.jobQueueId == null ? "nothing started" + (this.why == null ? "" : " (" + this.why + ")")
                : String.format("run %d with %d document(s)", this.jobQueueId, this.extractionIds.size());
        }
    }

    /**
     * The job's next run from its waiting documents, when the job may run now: the oldest, as many as the trigger's batch
     * size, into one run. Nothing when none waits, a run is in flight or the workspace is paused; a job that is gone or
     * switched off has its waiting documents Skipped. Serialised per job; run as the job's workspace.
     */
    public Started startNext(long jobId) {
        try {
            Started started = this.transactions.execute(status -> {
                this.store.lockJob(jobId);
                List<DocumentTriggerStore.Waiting> peek = this.store.waiting(jobId, 1);
                if (peek.isEmpty()) {
                    return new Started(null, Collections.emptyList(), "no document waits");
                }
                Optional<SourceJob> job = this.jobs.findByJobId(jobId);
                String refusal = refusal(job, job.map(SourceJob::getTenantId).orElse(null));
                if (refusal != null) {
                    int skipped = this.store.skipWaiting(jobId, refusal);
                    logger.info("Job {}: {} waiting approved document(s) skipped: {}", jobId, skipped, refusal);
                    return new Started(null, Collections.emptyList(), refusal);
                }
                Optional<String> paused = this.engine.workspacePause(job.get().getTenantId());
                if (paused.isPresent()) {
                    return new Started(null, Collections.emptyList(), "the workspace is " + paused.get());
                }
                if (this.store.inFlight(jobId)) {
                    return new Started(null, Collections.emptyList(), "a run is in flight");
                }
                Optional<DocumentTriggerStore.Trigger> trigger = this.store.find(jobId);
                int batch = trigger.map(t -> t.batchSize).orElse(1);
                List<DocumentTriggerStore.Waiting> documents = this.store.waiting(jobId,
                    Math.max(1, Math.min(batch, DocumentTriggerStore.MAX_BATCH)));
                List<Long> extractionIds = new ArrayList<>(documents.size());
                List<Long> rows = new ArrayList<>(documents.size());
                for (DocumentTriggerStore.Waiting document : documents) {
                    extractionIds.add(document.extractionId);
                    rows.add(document.documentApprovalId);
                }
                long type = trigger.map(t -> t.documentTypeId).orElse(0L);
                JobQueue run = this.engine.addDocumentJobInQueue(job.get(), type, extractionIds);
                this.store.started(rows, run.getJobQueueId());
                return new Started(run.getJobQueueId(), extractionIds, null);
            });
            return started == null ? Started.NONE : started;
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                // Another start (Run now, the scheduler, the inbox) took the slot between the check and the insert: they wait for it.
                return new Started(null, Collections.emptyList(), "another run took the slot");
            }
            throw failed;
        }
    }

    /** Run now's rules that stop a job for good, in its words; null when the job may run. */
    private static String refusal(Optional<SourceJob> job, Long tenantId) {
        if (!job.isPresent() || job.get().getTenantId() == null || tenantId == null || !job.get().getTenantId().equals(tenantId)) {
            return GONE;
        }
        if (job.get().getJobStatus() != Status.Active) {
            return String.format("The job is not active (%s), so the approved document did not start it.", job.get().getJobStatus());
        }
        return null;
    }

    // ---- the console ---------------------------------------------------------------------------------------------------

    /** A job's document trigger: whether it has one, whether it is on, its type, batch size and how many documents wait. */
    public ResponseDto trigger(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Optional<DocumentTriggerStore.Trigger> trigger = this.store.find(job.get().getJobId());
        return new ResponseDto(SUCCESS, trigger.isPresent() ? "Document trigger fetched successfully." : "This job has no document trigger.",
            DocumentTriggerView.of(job.get().getJobId(), trigger.orElse(null), this.store.waitingCount(job.get().getJobId())));
    }

    /**
     * UX review 2026-10-13 (DI1): every document trigger of the caller's workspace on a schedule the caller may see -- which
     * schedules an approved document of each type starts -- for Document Intelligence's Overview.
     */
    public ResponseDto triggers() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(SUCCESS, "Document triggers belong to a workspace.", new ArrayList<>());
        }
        List<Map<String, Object>> rows = this.store.listed(tenantId, JobOwnership.sqlPredicate("j"));
        return new ResponseDto(SUCCESS, String.format("%d document trigger(s).", rows.size()), rows);
    }

    /**
     * Sets a job's trigger: the document type (one the workspace may read), on or off, and how many waiting documents one
     * run takes (1..{@value DocumentTriggerStore#MAX_BATCH}; absent keeps the trigger's, 1 for a new one).
     */
    public ResponseDto save(Long jobId, Long documentTypeId, Boolean enabled, Integer batchSize) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Optional<DocumentTriggerStore.Trigger> saved = this.store.find(job.get().getJobId());
        Long type = documentTypeId != null ? documentTypeId : saved.map(t -> t.documentTypeId).orElse(null);
        if (type == null || type < 1) {
            return new ResponseDto(ERROR, "Choose the document type whose approved documents start this job (documentTypeId).");
        }
        if (batchSize != null && (batchSize < 1 || batchSize > DocumentTriggerStore.MAX_BATCH)) {
            return new ResponseDto(ERROR, String.format("batchSize is how many waiting documents one run takes: 1 to %d.",
                DocumentTriggerStore.MAX_BATCH));
        }
        String typeName = null;
        if (!saved.isPresent() || saved.get().documentTypeId != type) {
            try {
                typeName = this.typeName(job.get().getTenantId(), type);
            } catch (IllegalArgumentException notTheWorkspaces) {
                return new ResponseDto(ERROR, String.format("There is no document type %d in this workspace.", type));
            } catch (Exception unreachable) {
                return new ResponseDto(ERROR, "The document type could not be checked with Document Intelligence; try again in a moment.");
            }
        }
        int batch = batchSize != null ? batchSize : saved.map(t -> t.batchSize).orElse(1);
        boolean on = enabled == null || enabled;
        this.store.save(job.get().getJobId(), type, on, batch, TenantContext.getAppUserId());
        String what = typeName == null ? "type " + type : String.format("'%s'", typeName);
        String batching = batch == 1 ? " Documents approved while a run is going wait, and each starts its own run in turn."
            : String.format(" Documents approved while a run is going wait; the next run takes up to %d of them.", batch);
        return new ResponseDto(SUCCESS, on ? String.format("Every document of %s that is approved starts this job.", what) + batching
            : "The document trigger is off; approved documents do not start this job.",
            DocumentTriggerView.of(job.get().getJobId(), this.store.find(job.get().getJobId()).orElse(null),
                this.store.waitingCount(job.get().getJobId())));
    }

    /** The type's name, when the workspace may read its documents; IllegalArgumentException when it may not. */
    private String typeName(Long tenantId, long type) throws Exception {
        if (this.documents == null || tenantId == null) {
            return null;
        }
        DocumentDatasets.Request look = new DocumentDatasets.Request();
        look.tenantId = tenantId;
        look.documentTypeId = type;
        look.limit = 1;
        look.names = false;
        return this.documents.read(look).name;
    }

    public ResponseDto delete(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        return new ResponseDto(SUCCESS, this.store.delete(job.get().getJobId()) ? "Document trigger removed." : "This job has no document trigger.");
    }

    /**
     * What approved documents did to this job, newest first: Started (the run), Waiting (its place in the line: 1 is taken
     * by the next run) or Skipped (why).
     */
    public ResponseDto approvals(Long jobId, int limit) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        int size = limit <= 0 ? DEFAULT_APPROVALS : Math.min(limit, MAX_APPROVALS);
        List<Map<String, Object>> approvals = this.store.approvals(job.get().getJobId(), size);
        return new ResponseDto(SUCCESS, String.format("%d approved document(s).", approvals.size()), approvals);
    }

    private Optional<SourceJob> visibleJob(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return this.jobs.findByJobId(jobId).filter(JobOwnership::isVisibleToCaller);
    }
}
