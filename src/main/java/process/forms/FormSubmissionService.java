package process.forms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(FormSubmissionService.class);

    private final FormStore store;
    private final FormService forms;
    private final FormInbox inbox;
    private final BucketStore buckets;
    private final ProducerBulkEngine engine;
    private final TransactionServiceImpl jobs;
    private final TransactionTemplate transactions;

    public FormSubmissionService(FormStore store, FormService forms, FormInbox inbox, BucketStore buckets, ProducerBulkEngine engine,
        TransactionServiceImpl jobs, PlatformTransactionManager transactionManager) {
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
        try {
            answers = FormFields.answers(form.fields, request.getAnswers());
        } catch (FormFields.Unanswered unanswered) {
            Map<String, Object> problems = new LinkedHashMap<>();
            problems.put("problems", unanswered.getProblems());
            return new ResponseDto(ERROR, unanswered.getMessage(), problems);
        }
        FormStore.Submission received = this.transactions.execute(status -> this.store.receive(tenantId, form.formId, form.version,
            answers, form.jobId, TenantContext.getAppUserId(), TenantContext.getUsername()));
        if (form.jobId == null) {
            return new ResponseDto(SUCCESS, "Thank you: your submission was received.", view(received));
        }
        FormStore.Submission outcome = this.start(tenantId, form, received);
        return new ResponseDto(SUCCESS, FormStore.RUN_STARTED.equals(outcome.status)
            ? String.format("Thank you: your submission was received and started run #%d.", outcome.jobQueueId)
            : "Your submission was received, but it did not start its run: " + outcome.reason, view(outcome));
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
        for (FormStore.Submission submission : this.store.submissions(tenantId, form.get().formId, size)) {
            rows.add(view(submission));
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
            .map(s -> new ResponseDto(SUCCESS, "Submission fetched successfully.", view(s)))
            .orElseGet(() -> new ResponseDto(ERROR, SUBMISSION_NOT_FOUND));
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
        for (String column : new String[] {"Submission", "Submitted at", "Submitted by", "Status", "Run", "Reason"}) {
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
            for (String key : keys) {
                Object answer = s.answers.get(key);
                cells.add(answer == null ? "" : answer instanceof Boolean ? ((Boolean) answer ? "Yes" : "No") : String.valueOf(answer));
            }
            line(out, cells);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
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
        return view;
    }
}
