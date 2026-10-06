package process.forms;

import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Collection;

/** {@link FormStore} in memory, for the service tests: each statement keeps to the workspace it names, as the SQL does. */
class InMemoryFormStore implements FormStore {

    final Map<Long, Form> forms = new LinkedHashMap<>();
    final Map<Long, Submission> submissions = new LinkedHashMap<>();
    final List<Map<String, Object>> jobs = new ArrayList<>();
    private long nextForm = 1000;
    private long nextSubmission = 5000;

    @Override
    public List<Form> list(long tenantId, boolean withArchived, boolean activeOnly) {
        return this.forms.values().stream().filter(f -> f.tenantId == tenantId)
            .filter(f -> activeOnly ? ACTIVE.equals(f.status) : withArchived || !ARCHIVED.equals(f.status))
            .map(this::counted).sorted(Comparator.comparing(f -> f.name.toLowerCase())).collect(Collectors.toList());
    }

    @Override
    public Optional<Form> find(long tenantId, long formId) {
        return Optional.ofNullable(this.forms.get(formId)).filter(f -> f.tenantId == tenantId).map(this::counted);
    }

    private Form counted(Form f) {
        long count = this.submissions.values().stream().filter(s -> s.formId == f.formId).count();
        return carry(f, new Form(f.formId, f.tenantId, f.name, f.description, f.status, f.fields, f.jobId, f.version, f.updatedBy,
            f.dateCreated, f.dateUpdated, count));
    }

    /** A form's MIG-279 settings, kept across the copies this store makes. */
    private static Form carry(Form from, Form to) {
        to.workflowKey = from.workflowKey;
        to.analyticsDatasetId = from.analyticsDatasetId;
        to.datasetBucket = from.datasetBucket;
        return to;
    }

    @Override
    public long create(long tenantId, String name, String description, String status, List<FormField> fields, Long jobId, Long actor) {
        this.unique(tenantId, name, null);
        long id = this.nextForm++;
        this.forms.put(id, new Form(id, tenantId, name, description, status, fields, jobId, 1, actor, Instant.now(), null, 0));
        return id;
    }

    @Override
    public boolean update(long tenantId, long formId, String name, String description, String status, List<FormField> fields, Long jobId,
        Long actor) {
        Form f = this.forms.get(formId);
        if (f == null || f.tenantId != tenantId) {
            return false;
        }
        this.unique(tenantId, name, formId);
        this.forms.put(formId, carry(f, new Form(formId, tenantId, name, description, status, fields, jobId, f.version + 1, actor,
            f.dateCreated, Instant.now(), 0)));
        return true;
    }

    private void unique(long tenantId, String name, Long except) {
        for (Form f : this.forms.values()) {
            if (f.tenantId == tenantId && f.name.equalsIgnoreCase(name) && (except == null || f.formId != except)) {
                throw new DuplicateKeyException("ux_form_definition_tenant_name");
            }
        }
    }

    @Override
    public boolean setStatus(long tenantId, long formId, String status, Long actor) {
        Form f = this.forms.get(formId);
        if (f == null || f.tenantId != tenantId) {
            return false;
        }
        this.forms.put(formId, carry(f, new Form(formId, tenantId, f.name, f.description, status, f.fields, f.jobId, f.version, actor,
            f.dateCreated, Instant.now(), 0)));
        return true;
    }

    @Override
    public Submission receive(long tenantId, long formId, int formVersion, Map<String, Object> answers, Long jobId, Long submittedBy,
        String submittedByName) {
        long id = this.nextSubmission++;
        Submission s = new Submission(id, formId, this.forms.get(formId).tenantId, formVersion, answers, submittedBy, submittedByName,
            Instant.parse("2026-10-02T15:04:05Z"), RECEIVED, jobId, null, null, null, null);
        this.submissions.put(id, s);
        return s;
    }

    @Override
    public void outcome(long tenantId, long submissionId, String status, Long jobQueueId, String reason, String bucket, String storageKey) {
        Submission s = this.submissions.get(submissionId);
        if (s == null || s.tenantId != tenantId) {
            return;
        }
        Submission next = new Submission(s.submissionId, s.formId, s.tenantId, s.formVersion, s.answers, s.submittedBy,
            s.submittedByName, s.submittedAt, status, s.jobId, jobQueueId, reason, bucket, storageKey);
        next.workflowInstanceId = s.workflowInstanceId;
        next.workflowStatus = s.workflowStatus;
        next.workflowReason = s.workflowReason;
        this.submissions.put(submissionId, next);
    }

    @Override
    public List<Submission> submissions(long tenantId, long formId, int limit) {
        List<Submission> found = this.submissions.values().stream().filter(s -> s.tenantId == tenantId && s.formId == formId)
            .sorted(Comparator.comparing((Submission s) -> s.submissionId).reversed()).limit(limit).collect(Collectors.toList());
        return found;
    }

    @Override
    public Optional<Submission> submission(long tenantId, long submissionId) {
        return Optional.ofNullable(this.submissions.get(submissionId)).filter(s -> s.tenantId == tenantId);
    }

    @Override
    public List<Map<String, Object>> linkableJobs(long tenantId, int limit) {
        return this.jobs.stream().filter(j -> ((Long) j.get("tenantId")) == tenantId).limit(limit).collect(Collectors.toList());
    }

    /** Uploads by id: form, field, uploader, the upload, and the submission that took it (0 when none). */
    final Map<Long, Object[]> uploads = new LinkedHashMap<>();
    private long nextUpload = 7000;

    @Override
    public Optional<List<FormField>> fieldsAt(long tenantId, long formId, int version) {
        return Optional.empty();
    }

    @Override
    public List<String> answerValues(long tenantId, long formId, String fieldKey, int limit) {
        List<String> values = new ArrayList<>();
        for (Submission s : this.submissions.values()) {
            Object value = s.tenantId == tenantId && s.formId == formId ? s.answers.get(fieldKey) : null;
            if (value != null && !values.contains(String.valueOf(value))) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    @Override
    public long createUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, String fileName, String contentType, long size,
        String bucket, String storageKey) {
        long id = this.nextUpload++;
        this.uploads.put(id, new Object[] {formId, fieldKey, uploadedBy,
            new FormFields.Upload(id, fileName, contentType, size, bucket, storageKey), 0L});
        return id;
    }

    @Override
    public Optional<FormFields.Upload> openUpload(long tenantId, long formId, String fieldKey, Long uploadedBy, long uploadId) {
        Object[] u = this.uploads.get(uploadId);
        return u != null && (long) u[0] == formId && fieldKey.equals(u[1]) && Objects.equals(uploadedBy, u[2]) && (long) u[4] == 0L
            ? Optional.of((FormFields.Upload) u[3]) : Optional.empty();
    }

    @Override
    public void claimUploads(long tenantId, long submissionId, Collection<Long> uploadIds) {
        for (Long id : uploadIds) {
            Object[] u = this.uploads.get(id);
            if (u == null || (long) u[4] != 0L) {
                throw new IllegalStateException("Upload " + id + " was sent with another submission.");
            }
            u[4] = submissionId;
        }
    }

    @Override
    public void setFormWorkflow(long tenantId, long formId, String workflowKey) {
        this.forms.get(formId).workflowKey = workflowKey;
    }

    @Override
    public void setDataset(long tenantId, long formId, Long analyticsDatasetId, String bucket) {
        Form form = this.forms.get(formId);
        if (analyticsDatasetId != null) {
            form.analyticsDatasetId = analyticsDatasetId;
        }
        if (bucket != null) {
            form.datasetBucket = bucket;
        }
    }

    @Override
    public boolean setWorkflow(long tenantId, long submissionId, Long instanceId, String status, String reason) {
        Submission s = this.submissions.get(submissionId);
        if (s == null || s.tenantId != tenantId) {
            return false;
        }
        if (instanceId != null) {
            s.workflowInstanceId = instanceId;
        }
        s.workflowStatus = status;
        s.workflowReason = reason;
        return true;
    }

    @Override
    public void setStage(long tenantId, long submissionId, String stage) {
        Submission s = this.submissions.get(submissionId);
        if (s != null && s.tenantId == tenantId) {
            s.workflowStage = stage;
        }
    }
}
