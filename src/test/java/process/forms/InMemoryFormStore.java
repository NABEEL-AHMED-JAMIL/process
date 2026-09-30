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
        return new Form(f.formId, f.tenantId, f.name, f.description, f.status, f.fields, f.jobId, f.version, f.updatedBy, f.dateCreated,
            f.dateUpdated, count);
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
        this.forms.put(formId, new Form(formId, tenantId, name, description, status, fields, jobId, f.version + 1, actor, f.dateCreated,
            Instant.now(), 0));
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
        this.forms.put(formId, new Form(formId, tenantId, f.name, f.description, status, f.fields, f.jobId, f.version, actor, f.dateCreated,
            Instant.now(), 0));
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
        this.submissions.put(submissionId, new Submission(s.submissionId, s.formId, s.tenantId, s.formVersion, s.answers, s.submittedBy,
            s.submittedByName, s.submittedAt, status, s.jobId, jobQueueId, reason, bucket, storageKey));
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
}
