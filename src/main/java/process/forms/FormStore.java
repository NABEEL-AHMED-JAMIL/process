package process.forms;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * etl_job's form_definition and form_submission (V192). Every statement names the workspace, and row security keeps
 * each to the session's workspace as well, so a caller with no workspace reads and writes nothing.
 */
public interface FormStore {

    String DRAFT = "Draft";
    String ACTIVE = "Active";
    String ARCHIVED = "Archived";

    String RECEIVED = "Received";
    String RUN_STARTED = "RunStarted";
    String RUN_NOT_STARTED = "RunNotStarted";

    /** A form as kept. */
    final class Form {
        public final long formId;
        public final long tenantId;
        public final String name;
        public final String description;
        public final String status;
        public final List<FormField> fields;
        public final Long jobId;
        public final int version;
        public final Long updatedBy;
        public final Instant dateCreated;
        public final Instant dateUpdated;
        public final long submissions;

        public Form(long formId, long tenantId, String name, String description, String status, List<FormField> fields, Long jobId,
            int version, Long updatedBy, Instant dateCreated, Instant dateUpdated, long submissions) {
            this.formId = formId;
            this.tenantId = tenantId;
            this.name = name;
            this.description = description;
            this.status = status;
            this.fields = fields;
            this.jobId = jobId;
            this.version = version;
            this.updatedBy = updatedBy;
            this.dateCreated = dateCreated;
            this.dateUpdated = dateUpdated;
            this.submissions = submissions;
        }
    }

    /** A submission as kept. */
    final class Submission {
        public final long submissionId;
        public final long formId;
        public final long tenantId;
        public final int formVersion;
        public final Map<String, Object> answers;
        public final Long submittedBy;
        public final String submittedByName;
        public final Instant submittedAt;
        public final String status;
        public final Long jobId;
        public final Long jobQueueId;
        public final String reason;
        public final String bucket;
        public final String storageKey;

        public Submission(long submissionId, long formId, long tenantId, int formVersion, Map<String, Object> answers, Long submittedBy,
            String submittedByName, Instant submittedAt, String status, Long jobId, Long jobQueueId, String reason, String bucket,
            String storageKey) {
            this.submissionId = submissionId;
            this.formId = formId;
            this.tenantId = tenantId;
            this.formVersion = formVersion;
            this.answers = answers;
            this.submittedBy = submittedBy;
            this.submittedByName = submittedByName;
            this.submittedAt = submittedAt;
            this.status = status;
            this.jobId = jobId;
            this.jobQueueId = jobQueueId;
            this.reason = reason;
            this.bucket = bucket;
            this.storageKey = storageKey;
        }
    }

    /** The workspace's forms by name, each with its submission count; Archived ones only when asked. */
    List<Form> list(long tenantId, boolean withArchived, boolean activeOnly);

    Optional<Form> find(long tenantId, long formId);

    /** A new form; its id; its first version's fields are kept (form_version). DuplicateKeyException when the name is taken. */
    long create(long tenantId, String name, String description, String status, List<FormField> fields, Long jobId, Long actor);

    /** Replaces a form's content, one version on (kept in form_version); false when it is not the workspace's. */
    boolean update(long tenantId, long formId, String name, String description, String status, List<FormField> fields, Long jobId,
        Long actor);

    boolean setStatus(long tenantId, long formId, String status, Long actor);

    /** A new submission, Received; its row. */
    Submission receive(long tenantId, long formId, int formVersion, Map<String, Object> answers, Long jobId, Long submittedBy,
        String submittedByName);

    /** Records what the submission did: RunStarted (the run) or RunNotStarted (why), and the file it left, if any. */
    void outcome(long tenantId, long submissionId, String status, Long jobQueueId, String reason, String bucket, String storageKey);

    /** A form's submissions, newest first. */
    List<Submission> submissions(long tenantId, long formId, int limit);

    Optional<Submission> submission(long tenantId, long submissionId);

    /** The fields a form had at a version (MIG-277); empty for a version saved before versions were kept. */
    Optional<List<FormField>> fieldsAt(long tenantId, long formId, int version);

    /** The distinct answers a form's field has collected, newest first: what a lookup on it offers. */
    List<String> answerValues(long tenantId, long formId, String fieldKey, int limit);

    /** Records an uploaded file for a form's field; its id. */
    long createUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, String fileName, String contentType, long size,
        String bucket, String storageKey);

    /** The upload, when this person made it for this form's field and no submission has taken it yet. */
    Optional<FormFields.Upload> openUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, long uploadId);

    /** The submission takes these uploads; each can be taken once. */
    void claimUploads(long tenantId, long submissionId, Collection<Long> uploadIds);

    /** The workspace's jobs a form may start (any not deleted), by name: id, name, status. */
    List<Map<String, Object>> linkableJobs(long tenantId, int limit);
}
