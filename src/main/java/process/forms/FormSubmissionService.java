package process.forms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import process.pipeline.backing.BucketStore;
import process.security.TenantContext;
import process.util.BusinessTime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.UUID;
import java.util.Locale;
import java.util.HashMap;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Wave 5 Forms (lite), the submissions: a member fills in an Active form of their workspace; the answers are checked
 * ({@link FormFields}) and stored, and when the form names a job, the submission starts it.
 *
 * <p><b>How a submission starts a run.</b> The submission is stored first (Received), in a transaction of its own, so it
 * is kept whatever happens next. Then, when the form names a job:
 * <ol>
 *   <li>Run now's rules, in the inbox's words where it has them (InboxTriggerService): the job must still be the
 *       workspace's, Active, its workspace not paused, and have no run in flight (Queue, Start, Running) -- the
 *       one-run-in-flight rule;</li>
 *   <li>the workspace's inbox (storage-service, asked as the person submitting) names the bucket;</li>
 *   <li>the submission is written there as JSON -- one object: submission_id, form_id, form_name, form_version,
 *       submitted_by, submitted_at, then every answer by its field's key -- at
 *       intake/forms/form-&lt;id&gt;/yyyy/MM/dd/submission-&lt;id&gt;.json (Chicago's date), through Core's trusted
 *       pipeline caller (CORE_PIPELINES, BucketStore) in the workspace's own connection; storage-service announces no
 *       inbox arrival for it, so no inbox trigger starts a second run;</li>
 *   <li>the job is queued with that file as its input (job_queue.input_bucket / input_key: ProducerBulkEngine.
 *       addFormJobInQueue), in one transaction with the submission's RunStarted and the run's id -- so a step pipeline
 *       whose first step is Read CSV/JSON/Parquet with no bucket and key reads the submission as one row.</li>
 * </ol>
 * <b>A job that is busy, or any other refusal, does not lose the submission</b>: it stays, marked RunNotStarted with the
 * reason (the file, when it was already written, is named on it too), and nothing retries it -- the submission is not
 * queued for later. Someone sends the form again, or runs the job, once the run in flight has finished. (Decided
 * 2026-09-30 for the demo: the inbox answers a busy job the same way, as Skipped.)
 *
 * <p>Reading submissions -- a form's list, one submission, the CSV export -- is for whoever holds the Submissions page
 * ('form-submissions'), for every form of their workspace; another workspace's reads as not found.
 */
@Service
public class FormSubmissionService {

    static final String SUBMISSION_NOT_FOUND = "Submission not found.";
    static final String CLOSED = "This form is not taking submissions.";
    static final int DEFAULT_LIST = 100;
    static final int MAX_LIST = 500;
    static final int MAX_EXPORT = 10_000;
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper CSV_JSON = new ObjectMapper();

    private static final Logger logger = LoggerFactory.getLogger(FormSubmissionService.class);

    private final FormStore store;
    private final FormService forms;
    private final FormInbox inbox;
    private final BucketStore buckets;
    private final ProducerBulkEngine engine;
    private final TransactionServiceImpl jobs;
    private final TransactionTemplate transactions;
    private final FormWorkflows workflows;

    /** Submissions without workflows: what the plain-forms tests build. */
    public FormSubmissionService(FormStore store, FormService forms, FormInbox inbox, BucketStore buckets, ProducerBulkEngine engine,
        TransactionServiceImpl jobs, PlatformTransactionManager transactionManager) {
        this(store, forms, inbox, buckets, engine, jobs, transactionManager, null);
    }

    @Autowired
    public FormSubmissionService(FormStore store, FormService forms, FormInbox inbox, BucketStore buckets, ProducerBulkEngine engine,
        TransactionServiceImpl jobs, PlatformTransactionManager transactionManager, FormWorkflows workflows) {
        this.workflows = workflows;
        this.store = store;
        this.forms = forms;
        this.inbox = inbox;
        this.buckets = buckets;
        this.engine = engine;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- submitting --------------------------------------------------------------------------------------------

    public ResponseDto submit(FormSubmitRequest request) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        Optional<FormStore.Form> found = this.forms.visible(tenantId, request == null ? null : request.getFormId());
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, FormService.FORM_NOT_FOUND);
        }
        FormStore.Form form = found.get();
        if (!FormStore.ACTIVE.equals(form.status)) {
            return new ResponseDto(ERROR, CLOSED + " It is " + form.status + "; make it Active first.");
        }
        Map<String, Object> answers;
        Long person = TenantContext.getAppUserId();
        try {
            answers = FormFields.answers(form.fields, request.getAnswers(), this.context(tenantId, form, person));
        } catch (FormFields.Unanswered unanswered) {
            Map<String, Object> problems = new LinkedHashMap<>();
            problems.put("problems", unanswered.getProblems());
            return new ResponseDto(ERROR, unanswered.getMessage(), problems);
        }
        List<Long> uploads = uploadIds(form.fields, answers);
        FormStore.Submission received;
        try {
            received = this.transactions.execute(status -> {
                FormStore.Submission kept = this.store.receive(tenantId, form.formId, form.version, answers, form.jobId, person,
                    TenantContext.getUsername());
                this.store.claimUploads(tenantId, kept.submissionId, uploads);
                return kept;
            });
        } catch (IllegalStateException sentTwice) {
            return new ResponseDto(ERROR, "A file on this form was just sent with another submission; upload it again and send.");
        }
        FormStore.Submission outcome = form.jobId == null ? received : this.start(tenantId, form, received);
        String message = form.jobId == null ? "Thank you: your submission was received."
            : FormStore.RUN_STARTED.equals(outcome.status)
            ? String.format("Thank you: your submission was received and started run #%d.", outcome.jobQueueId)
            : "Your submission was received, but it did not start its run: " + outcome.reason;
        if (form.workflowKey != null) {
            outcome = this.startWorkflow(tenantId, form, outcome, person);
            message += FormStore.NOT_STARTED.equals(outcome.workflowStatus)
                ? " Its approval did not start: " + outcome.workflowReason
                : String.format(" Its approval (request #%d) is %s.", outcome.workflowInstanceId, outcome.workflowStatus.toLowerCase(Locale.ROOT));
        }
        this.writeRow(tenantId, form, outcome, true);
        return new ResponseDto(SUCCESS, message, view(outcome));
    }

    // ---- workflow and dataset (MIG-279) --------------------------------------------------------------------------

    /** Starts the form's workflow for the submission; a failure is kept on it as NotStarted, with why. */
    private FormStore.Submission startWorkflow(long tenantId, FormStore.Form form, FormStore.Submission submission, Long person) {
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("form", form.name);
        subject.put("submission_id", submission.submissionId);
        subject.put("submitted_by", submission.submittedByName);
        for (Map.Entry<String, Object> answer : submission.answers.entrySet()) {
            subject.put(answer.getKey(), FormDatasets.flat(typeOf(form, answer.getKey()), answer.getValue()));
        }
        String title = String.format("%s #%d", form.name, submission.submissionId);
        try {
            FormWorkflows.Started started = this.workflows.start(tenantId, form.workflowKey, submission.submissionId,
                title.length() > 255 ? title.substring(0, 255) : title, subject, person);
            this.store.setWorkflow(tenantId, submission.submissionId, started.instanceId, statusOf(started.state, false), null);
        } catch (RuntimeException refused) {
            logger.warn("Submission {} of form {} did not start workflow {}: {}", submission.submissionId, form.formId, form.workflowKey,
                refused.getMessage());
            String reason = refused.getMessage() == null ? "workflow-service did not answer" : refused.getMessage();
            this.store.setWorkflow(tenantId, submission.submissionId, null, FormStore.NOT_STARTED,
                reason.length() > 2000 ? reason.substring(0, 2000) : reason);
        }
        return this.store.submission(tenantId, submission.submissionId).orElse(submission);
    }

    /**
     * A request's change, from workflow-service's instance-changed event: the submission's status follows it, and its
     * dataset row is written again. False when the submission is not this workspace's (or is gone).
     */
    public boolean followWorkflow(long tenantId, long submissionId, long instanceId, String state, boolean overdue) {
        if (!this.store.setWorkflow(tenantId, submissionId, instanceId, statusOf(state, overdue), null)) {
            return false;
        }
        this.store.submission(tenantId, submissionId).ifPresent(s ->
            this.store.find(tenantId, s.formId).ifPresent(form -> this.writeRow(tenantId, form, s, false)));
        return true;
    }

    /** A request's state as a submission's status: Pending (or Overdue) while it runs, else how it ended. */
    static String statusOf(String state, boolean overdue) {
        if (state == null) {
            return "Pending";
        }
        switch (state) {
            case "Approved":
            case "Rejected":
            case "Completed":
            case "Cancelled":
            case "Failed":
                return state;
            default:
                return overdue ? "Overdue" : "Pending";
        }
    }

    /**
     * The submission as its form's dataset row (FormDatasets), in the bucket the form's rows are kept in -- the inbox, the
     * first time, as the person submitting. Best effort: a row that cannot be written is logged; the submission stands.
     */
    private void writeRow(long tenantId, FormStore.Form form, FormStore.Submission submission, boolean mayLocate) {
        String bucket = form.datasetBucket;
        try {
            if (bucket == null && mayLocate) {
                bucket = this.inbox.locate().alias;
                if (bucket != null) {
                    this.store.setDataset(tenantId, form.formId, null, bucket);
                }
            }
            if (bucket == null || this.buckets.unavailable().isPresent()) {
                return;
            }
            List<FormField> fields = this.store.fieldsAt(tenantId, form.formId, submission.formVersion).orElse(form.fields);
            this.buckets.upload(tenantId, bucket, FormDatasets.keyOf(form.formId, submission.submissionId),
                CSV_JSON.writeValueAsBytes(FormDatasets.rowOf(fields, submission)), "application/json");
        } catch (Exception unwritten) {
            logger.warn("Submission {}'s dataset row was not written to {}: {}", submission.submissionId, bucket, unwritten.getMessage());
        }
    }

    private static String typeOf(FormStore.Form form, String key) {
        return form.fields.stream().filter(f -> f.getKey().equals(key)).map(FormField::getType).findFirst().orElse(null);
    }

    /** Lookups' values and this person's own open uploads to this form, for checking the answers. */
    private FormFields.Context context(long tenantId, FormStore.Form form, Long person) {
        return new FormFields.Context() {
            @Override
            public List<String> lookupValues(FormField field) {
                return FormSubmissionService.this.forms.lookupValues(tenantId, field);
            }

            @Override
            public Optional<FormFields.Upload> upload(String fieldKey, long uploadId) {
                return FormSubmissionService.this.store.openUpload(tenantId, form.formId, fieldKey, person, uploadId);
            }
        };
    }

    /** The uploads the kept answers name. */
    @SuppressWarnings("unchecked")
    static List<Long> uploadIds(List<FormField> fields, Map<String, Object> answers) {
        List<Long> ids = new ArrayList<>();
        for (FormField field : fields) {
            Object answer = answers.get(field.getKey());
            List<Object> named = answer instanceof List ? (List<Object>) answer : answer == null ? new ArrayList<>()
                : Collections.singletonList(answer);
            if (!FormFields.UPLOAD_TYPES.contains(field.getType())) {
                continue;
            }
            for (Object upload : named) {
                if (upload instanceof Map && ((Map<String, Object>) upload).get("uploadId") instanceof Number) {
                    ids.add(((Number) ((Map<String, Object>) upload).get("uploadId")).longValue());
                }
            }
        }
        return ids;
    }

    // ---- uploading ---------------------------------------------------------------------------------------------

    /**
     * A file (or a drawn signature) for a form's field, before the form is sent: checked against the field -- its
     * accepted extensions and size, or a PNG of at most 512 KB for a signature -- written to the workspace's inbox
     * bucket under intake/forms/form-N/uploads/, and recorded as this person's for this field. The answer names it by
     * uploadId; only its uploader can send it, once.
     */
    public ResponseDto upload(Long formId, String fieldKey, String originalName, byte[] content) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        Optional<FormStore.Form> found = this.forms.visible(tenantId, formId);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, FormService.FORM_NOT_FOUND);
        }
        FormStore.Form form = found.get();
        if (!FormStore.ACTIVE.equals(form.status)) {
            return new ResponseDto(ERROR, CLOSED + " It is " + form.status + "; make it Active first.");
        }
        FormField field = form.fields.stream().filter(f -> f.getKey().equals(fieldKey)).findFirst().orElse(null);
        if (field == null || !FormFields.UPLOAD_TYPES.contains(field.getType())) {
            return new ResponseDto(ERROR, "This form has no file or signature field '" + fieldKey + "'.");
        }
        String name = safeName(originalName);
        String extension = extensionOf(name);
        long size = content == null ? 0 : content.length;
        if (size == 0) {
            return new ResponseDto(ERROR, "The file is empty.");
        }
        if (FormFields.SIGNATURE.equals(field.getType())) {
            if (!isPng(content) || size > FormFields.MAX_SIGNATURE_BYTES) {
                return new ResponseDto(ERROR, "A signature is a drawn PNG of at most 512 KB; sign in the box again.");
            }
            extension = "png";
            name = "signature.png";
        } else {
            List<String> accept = field.getAccept() == null ? FormFields.DEFAULT_ACCEPT : field.getAccept();
            if (!accept.contains(extension)) {
                return new ResponseDto(ERROR, String.format("%s takes %s files; '%s' is not one.", field.getLabel(),
                    String.join(", ", accept), name));
            }
            long most = (field.getMaxSizeMb() == null ? 10L : field.getMaxSizeMb()) * 1024L * 1024L;
            if (size > most) {
                return new ResponseDto(ERROR, String.format("%s takes files of at most %d MB; '%s' is larger.", field.getLabel(),
                    most / (1024 * 1024), name));
            }
        }
        Optional<String> unavailable = this.buckets.unavailable();
        if (unavailable.isPresent()) {
            return new ResponseDto(ERROR, "Files cannot be stored yet (" + unavailable.get() + ").");
        }
        FormInbox.Location location = this.inbox.locate();
        if (location.alias == null) {
            return new ResponseDto(ERROR, "Files go to the workspace's inbox, and there is none: " + location.refusal);
        }
        String contentType = contentTypeOf(extension);
        String key = String.format("intake/forms/form-%d/uploads/%s/%s-%s", form.formId, BusinessTime.today().format(FOLDER),
            UUID.randomUUID(), name);
        try {
            this.buckets.upload(tenantId, location.alias, key, content, contentType);
        } catch (Exception unwritten) {
            logger.warn("An upload to form {} field {} could not be written to {}/{}: {}", form.formId, fieldKey, location.alias, key,
                unwritten.getMessage());
            return new ResponseDto(ERROR, "The file could not be stored (" + unwritten.getMessage() + "). Try again.");
        }
        String kept = originalName == null || originalName.trim().isEmpty() ? name : originalName.trim();
        String keptName = kept.length() > 255 ? name : kept;
        Long uploadId = this.transactions.execute(status -> this.store.createUpload(tenantId, form.formId, fieldKey,
            TenantContext.getAppUserId(), keptName, contentType, size, location.alias, key));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uploadId", uploadId);
        view.put("name", FormFields.SIGNATURE.equals(field.getType()) ? "signature.png" : name);
        view.put("contentType", contentType);
        view.put("size", size);
        return new ResponseDto(SUCCESS, "Uploaded.", view);
    }

    /** The file's own name, stripped of any path and of characters a key should not carry; at most 120 characters. */
    static String safeName(String original) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).trim();
        String safe = base.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("\\s+", "-");
        if (safe.isEmpty() || safe.matches("^\\.+$")) {
            safe = "file";
        }
        if (safe.length() > 120) {
            String extension = extensionOf(safe);
            safe = safe.substring(0, 120 - extension.length() - 1) + "." + extension;
        }
        return safe;
    }

    static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isPng(byte[] content) {
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (content == null || content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** The type the file is served as, from its extension -- never the browser's word for it. */
    static String contentTypeOf(String extension) {
        switch (extension) {
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "tif": case "tiff": return "image/tiff";
            case "heic": return "image/heic";
            case "csv": return "text/csv";
            case "txt": return "text/plain";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls": return "application/vnd.ms-excel";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc": return "application/msword";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }

    private FormStore.Submission start(long tenantId, FormStore.Form form, FormStore.Submission received) {
        Optional<SourceJob> job = this.jobs.findByJobId(form.jobId).filter(j -> j.getTenantId() != null && j.getTenantId() == tenantId);
        String refusal = this.refusal(job);
        if (refusal != null) {
            return this.notStarted(tenantId, received, refusal, null, null);
        }
        Optional<String> unavailable = this.buckets.unavailable();
        if (unavailable.isPresent()) {
            return this.notStarted(tenantId, received, "Pipelines cannot read the workspace's buckets yet (" + unavailable.get()
                + "), so the submission could not be handed to its job.", null, null);
        }
        FormInbox.Location location = this.inbox.locate();
        if (location.alias == null) {
            return this.notStarted(tenantId, received, location.refusal, null, null);
        }
        String key = keyOf(form, received, BusinessTime.today());
        try {
            this.buckets.upload(tenantId, location.alias, key, fileOf(form, received), "application/json");
        } catch (Exception unwritten) {
            logger.warn("Submission {} of form {} could not be written to {}/{}: {}", received.submissionId, form.formId, location.alias, key,
                unwritten.getMessage());
            return this.notStarted(tenantId, received, "The submission could not be written to the workspace inbox ("
                + unwritten.getMessage() + "), so it did not start its job.", null, null);
        }
        try {
            return this.transactions.execute(status -> {
                JobQueue run = this.engine.addFormJobInQueue(job.get(), location.alias, key, form.name, received.submissionId);
                this.store.outcome(tenantId, received.submissionId, FormStore.RUN_STARTED, run.getJobQueueId(), null, location.alias, key);
                return this.store.submission(tenantId, received.submissionId).orElseThrow(() -> new IllegalStateException(
                    "The submission is gone."));
            });
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                // Another start (Run now, the enqueuer, an inbox file, another submission) took the slot since the check.
                return this.notStarted(tenantId, received, busy(null), location.alias, key);
            }
            logger.error("Submission {} of form {} could not queue job {}", received.submissionId, form.formId, form.jobId, failed);
            return this.notStarted(tenantId, received, "Its job could not be queued. The submission is kept; a workspace admin can "
                + "run the job with its file.", location.alias, key);
        }
    }

    /** Run now's rules; null when the job may start. Never names the job: a member may not see it (JobOwnership). */
    private String refusal(Optional<SourceJob> job) {
        if (!job.isPresent() || job.get().getJobStatus() == Status.Delete) {
            return "The job this form starts no longer exists.";
        }
        if (job.get().getJobStatus() != Status.Active) {
            return String.format("The job this form starts is not active (%s).", job.get().getJobStatus());
        }
        Optional<String> paused = this.engine.workspacePause(job.get().getTenantId());
        if (paused.isPresent()) {
            return String.format("This workspace is %s, so its runs are paused.", paused.get());
        }
        if (job.get().getJobRunningStatus() != null && job.get().getJobRunningStatus().isInFlight()) {
            return busy(job.get().getJobRunningStatus().name());
        }
        return null;
    }

    static String busy(String status) {
        return "The job was busy: a run of it was still in flight" + (status == null ? "" : " ('" + status + "')")
            + ", so this submission did not start another. It is kept; send the form again, or run the job, once that run has finished.";
    }

    private FormStore.Submission notStarted(long tenantId, FormStore.Submission received, String reason, String bucket, String key) {
        this.transactions.executeWithoutResult(status -> this.store.outcome(tenantId, received.submissionId, FormStore.RUN_NOT_STARTED,
            null, reason, bucket, key));
        logger.info("Submission {} of form {} did not start its job: {}", received.submissionId, received.formId, reason);
        return this.store.submission(tenantId, received.submissionId).orElse(received);
    }

    /** Where the submission's file goes in the inbox bucket: under intake/forms/, by form and Chicago's day. */
    static String keyOf(FormStore.Form form, FormStore.Submission submission, LocalDate day) {
        return String.format("intake/forms/form-%d/%s/submission-%d.json", form.formId, day.format(FOLDER), submission.submissionId);
    }

    /** The submission as the pipeline reads it: one JSON object, the submission's own values first, then the answers. */
    static byte[] fileOf(FormStore.Form form, FormStore.Submission submission) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("submission_id", submission.submissionId);
        file.put("form_id", form.formId);
        file.put("form_name", form.name);
        file.put("form_version", submission.formVersion);
        file.put("submitted_by", submission.submittedByName);
        file.put("submitted_at", submission.submittedAt == null ? null : submission.submittedAt.atZone(BusinessTime.ZONE)
            .toOffsetDateTime().toString());
        file.putAll(submission.answers);
        try {
            return JSON.writeValueAsString(file).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("The submission could not be written as JSON.", unwritable);
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** A form's submissions, newest first, with their answers. */
    public ResponseDto list(Long formId, int limit) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        Optional<FormStore.Form> form = this.anyForm(tenantId, formId);
        if (!form.isPresent()) {
            return new ResponseDto(ERROR, FormService.FORM_NOT_FOUND);
        }
        int size = limit <= 0 ? DEFAULT_LIST : Math.min(limit, MAX_LIST);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Integer, Optional<List<FormField>>> versions = new HashMap<>();
        for (FormStore.Submission submission : this.store.submissions(tenantId, form.get().formId, size)) {
            Map<String, Object> row = view(submission);
            // A submission to an earlier version carries that version's fields, so it is shown as it was answered.
            if (submission.formVersion != form.get().version) {
                versions.computeIfAbsent(submission.formVersion, v -> this.fieldsOf(tenantId, submission))
                    .ifPresent(fields -> row.put("fields", fields));
            }
            rows.add(row);
        }
        return new ResponseDto(SUCCESS, String.format("%d submission(s).", rows.size()), rows);
    }

    public ResponseDto fetch(Long submissionId) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        if (submissionId == null) {
            return new ResponseDto(ERROR, SUBMISSION_NOT_FOUND);
        }
        return this.store.submission(tenantId, submissionId).filter(s -> s.tenantId == tenantId)
            .map(s -> {
                Map<String, Object> view = view(s);
                this.fieldsOf(tenantId, s).ifPresent(fields -> view.put("fields", fields));
                return new ResponseDto(SUCCESS, "Submission fetched successfully.", view);
            })
            .orElseGet(() -> new ResponseDto(ERROR, SUBMISSION_NOT_FOUND));
    }

    /** The fields the submission answered: its version's, kept since MIG-277. */
    private Optional<List<FormField>> fieldsOf(long tenantId, FormStore.Submission submission) {
        return this.store.fieldsAt(tenantId, submission.formId, submission.formVersion);
    }

    /** A CSV export of a form's submissions; null when the form is not the caller's workspace's. */
    public Export export(Long formId) {
        Long tenantId = FormService.workspace();
        Optional<FormStore.Form> form = tenantId == null ? Optional.<FormStore.Form>empty() : this.anyForm(tenantId, formId);
        if (!form.isPresent()) {
            return null;
        }
        List<FormStore.Submission> submissions = this.store.submissions(tenantId, form.get().formId, MAX_EXPORT);
        return new Export(fileName(form.get()), csv(form.get(), submissions));
    }

    /** A CSV file: its name and its bytes (UTF-8, with a byte-order mark so a spreadsheet reads the accents). */
    public static final class Export {
        public final String fileName;
        public final byte[] content;

        Export(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    /** Any form of the workspace, Archived ones too: their submissions stay readable. */
    private Optional<FormStore.Form> anyForm(long tenantId, Long formId) {
        return formId == null ? Optional.<FormStore.Form>empty()
            : this.store.find(tenantId, formId).filter(f -> f.tenantId == tenantId);
    }

    static byte[] csv(FormStore.Form form, List<FormStore.Submission> submissions) {
        List<String> keys = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (String column : new String[] {"Submission", "Submitted at", "Submitted by", "Status", "Run", "Reason", "Approval"}) {
            header.add(column);
        }
        for (FormField field : form.fields) {
            keys.add(field.getKey());
            header.add(field.getLabel());
        }
        // Answers to fields removed since: kept, under their key.
        Set<String> extra = new LinkedHashSet<>();
        for (FormStore.Submission submission : submissions) {
            for (String key : submission.answers.keySet()) {
                if (!keys.contains(key)) {
                    extra.add(key);
                }
            }
        }
        keys.addAll(extra);
        header.addAll(extra);
        StringBuilder out = new StringBuilder("﻿");
        line(out, header);
        for (FormStore.Submission s : submissions) {
            List<String> cells = new ArrayList<>();
            cells.add(String.valueOf(s.submissionId));
            cells.add(wallClock(s.submittedAt));
            cells.add(s.submittedByName == null ? (s.submittedBy == null ? "" : "User " + s.submittedBy) : s.submittedByName);
            cells.add(s.status);
            cells.add(s.jobQueueId == null ? "" : String.valueOf(s.jobQueueId));
            cells.add(s.reason == null ? "" : s.reason);
            cells.add(s.workflowStatus == null ? "" : s.workflowStatus);
            for (String key : keys) {
                Object answer = s.answers.get(key);
                cells.add(csvAnswer(answer));
            }
            line(out, cells);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An answer in one cell: yes/no in words, files by name, a signature as signed, a table's rows as JSON. */
    static String csvAnswer(Object answer) {
        if (answer == null) {
            return "";
        }
        if (answer instanceof Boolean) {
            return (Boolean) answer ? "Yes" : "No";
        }
        if (answer instanceof Map && ((Map<?, ?>) answer).containsKey("uploadId")) {
            return String.valueOf(((Map<?, ?>) answer).get("name"));
        }
        if (answer instanceof List) {
            List<?> items = (List<?>) answer;
            if (!items.isEmpty() && items.stream().allMatch(i -> i instanceof Map && ((Map<?, ?>) i).containsKey("uploadId"))) {
                List<String> names = new ArrayList<>();
                items.forEach(i -> names.add(String.valueOf(((Map<?, ?>) i).get("name"))));
                return String.join("; ", names);
            }
            try {
                return CSV_JSON.writeValueAsString(answer);
            } catch (JsonProcessingException unwritable) {
                return String.valueOf(answer);
            }
        }
        return String.valueOf(answer);
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** One CSV cell: quoted when it must be, and a leading =, +, - or @ defused so a spreadsheet does not run it. */
    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !text.matches("^-?\\d+(\\.\\d+)?$")) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String fileName(FormStore.Form form) {
        String slug = form.name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return (slug.isEmpty() ? "form-" + form.formId : slug) + "-submissions-" + BusinessTime.today() + ".csv";
    }

    private static String wallClock(Instant instant) {
        return instant == null ? "" : BusinessTime.wallClockOf(instant).format(WALL_CLOCK);
    }

    static Map<String, Object> view(FormStore.Submission s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("submissionId", s.submissionId);
        view.put("formId", s.formId);
        view.put("formVersion", s.formVersion);
        view.put("status", s.status);
        view.put("jobId", s.jobId);
        view.put("jobQueueId", s.jobQueueId);
        view.put("reason", s.reason);
        view.put("submittedBy", s.submittedBy);
        view.put("submittedByName", s.submittedByName);
        view.put("submittedAt", s.submittedAt == null ? null : s.submittedAt.toString());
        view.put("bucket", s.bucket);
        view.put("storageKey", s.storageKey);
        view.put("answers", s.answers);
        view.put("workflowInstanceId", s.workflowInstanceId);
        view.put("workflowStatus", s.workflowStatus);
        view.put("workflowReason", s.workflowReason);
        return view;
    }
}
