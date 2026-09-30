package process.forms;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Wave 5 Forms (lite), the builder's half: a workspace's forms -- name, description, status (Draft, Active, Archived),
 * fields ({@link FormFields}) and, optionally, the job each submission starts. Owner decision 2026-09-30: a form is
 * shared INSIDE its workspace only; there are no public or expiring links.
 *
 * <ul>
 *   <li>Building -- saving a form, changing its status, listing the jobs it may start -- is a workspace administrator's
 *       (@PreAuthorize on the controller, and here again), and a builder action (MIG-244): read-only for the customer in
 *       a MANAGED workspace.</li>
 *   <li>Reading is every member's who holds the form's page ('forms'): a member sees the Active forms only, without the
 *       job behind them (a tenant user sees only their own jobs, JobOwnership); an administrator sees every form.</li>
 * </ul>
 * Everything is the caller's own workspace's: another workspace's form reads as one that does not exist.
 */
@Service
public class FormService {

    static final String FORM_NOT_FOUND = "Form not found.";
    static final String NO_WORKSPACE = "Forms belong to a workspace; sign in to one to use them.";
    static final String ADMINS_ONLY = "Only a workspace admin builds forms.";
    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_LINKABLE_JOBS = 500;
    static final List<String> STATUSES = Arrays.asList(FormStore.DRAFT, FormStore.ACTIVE, FormStore.ARCHIVED);

    private final FormStore store;
    private final TransactionServiceImpl jobs;

    public FormService(FormStore store, TransactionServiceImpl jobs) {
        this.store = store;
        this.jobs = jobs;
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** The workspace's forms: every one but the Archived (or those too) for an administrator, the Active for a member. */
    public ResponseDto list(boolean withArchived) {
        Long tenantId = workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        boolean admin = isAdmin();
        List<Map<String, Object>> forms = new ArrayList<>();
        for (FormStore.Form form : this.store.list(tenantId, withArchived && admin, !admin)) {
            forms.add(this.view(form, admin, false));
        }
        return new ResponseDto(SUCCESS, String.format("%d form(s).", forms.size()), forms);
    }

    /** One form with its fields: any form of the workspace for an administrator, an Active one for a member. */
    public ResponseDto fetch(Long formId) {
        Long tenantId = workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        Optional<FormStore.Form> form = this.visible(tenantId, formId);
        return form.map(f -> new ResponseDto(SUCCESS, "Form fetched successfully.", this.view(f, isAdmin(), true)))
            .orElseGet(() -> new ResponseDto(ERROR, FORM_NOT_FOUND));
    }

    /** The form as the caller may see it; empty when it is not theirs to see. */
    Optional<FormStore.Form> visible(Long tenantId, Long formId) {
        if (tenantId == null || formId == null) {
            return Optional.empty();
        }
        return this.store.find(tenantId, formId).filter(f -> f.tenantId == tenantId && (isAdmin() || FormStore.ACTIVE.equals(f.status)));
    }

    /** The workspace's jobs a form may start: every one not deleted, by name. An administrator's. */
    public ResponseDto linkableJobs() {
        Long tenantId = workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        if (!isAdmin()) {
            return new ResponseDto(ERROR, ADMINS_ONLY);
        }
        List<Map<String, Object>> linkable = this.store.linkableJobs(tenantId, MAX_LINKABLE_JOBS);
        return new ResponseDto(SUCCESS, String.format("%d job(s).", linkable.size()), linkable);
    }

    // ---- building ----------------------------------------------------------------------------------------------

    /** Creates a form (no formId) or replaces one: name, description, status, fields, the job it starts. */
    public ResponseDto save(FormSaveRequest request) {
        Long tenantId = workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        if (!isAdmin()) {
            return new ResponseDto(ERROR, ADMINS_ONLY);
        }
        if (request == null) {
            return new ResponseDto(ERROR, "Send the form to save.");
        }
        if (request.getFormId() != null && !this.store.find(tenantId, request.getFormId()).isPresent()) {
            return new ResponseDto(ERROR, FORM_NOT_FOUND);
        }
        String name = FormFields.trimmed(request.getName());
        if (name == null) {
            return new ResponseDto(ERROR, "Give the form a name.");
        }
        if (name.length() > MAX_NAME) {
            return new ResponseDto(ERROR, String.format("A form's name is at most %d characters.", MAX_NAME));
        }
        String description = FormFields.trimmed(request.getDescription());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            return new ResponseDto(ERROR, String.format("A form's description is at most %d characters.", MAX_DESCRIPTION));
        }
        String status = request.getStatus() == null ? FormStore.DRAFT : request.getStatus().trim();
        if (!STATUSES.contains(status)) {
            return new ResponseDto(ERROR, "A form's status is Draft, Active or Archived.");
        }
        List<FormField> fields;
        try {
            fields = FormFields.valid(request.getFields());
        } catch (FormFields.Refused refused) {
            return new ResponseDto(ERROR, refused.getMessage());
        }
        if (FormStore.ACTIVE.equals(status) && fields.isEmpty()) {
            return new ResponseDto(ERROR, "Add at least one field before the form takes submissions (Active).");
        }
        String jobRefusal = this.jobRefusal(tenantId, request.getJobId());
        if (jobRefusal != null) {
            return new ResponseDto(ERROR, jobRefusal);
        }
        Long actor = TenantContext.getAppUserId();
        long formId;
        try {
            if (request.getFormId() == null) {
                formId = this.store.create(tenantId, name, description, status, fields, request.getJobId(), actor);
            } else {
                formId = request.getFormId();
                if (!this.store.update(tenantId, formId, name, description, status, fields, request.getJobId(), actor)) {
                    return new ResponseDto(ERROR, FORM_NOT_FOUND);
                }
            }
        } catch (DuplicateKeyException taken) {
            return new ResponseDto(ERROR, String.format("This workspace already has a form named '%s'.", name));
        }
        return new ResponseDto(SUCCESS, request.getFormId() == null ? "Form created." : "Form saved.",
            this.view(this.store.find(tenantId, formId).orElseThrow(() -> new IllegalStateException("The saved form is gone.")), true, true));
    }

    /** Draft, Active (takes submissions) or Archived (takes none, kept with its submissions). */
    public ResponseDto status(FormStatusRequest request) {
        Long tenantId = workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        if (!isAdmin()) {
            return new ResponseDto(ERROR, ADMINS_ONLY);
        }
        if (request == null || request.getFormId() == null) {
            return new ResponseDto(ERROR, FORM_NOT_FOUND);
        }
        Optional<FormStore.Form> form = this.store.find(tenantId, request.getFormId());
        if (!form.isPresent()) {
            return new ResponseDto(ERROR, FORM_NOT_FOUND);
        }
        String status = request.getStatus() == null ? "" : request.getStatus().trim();
        if (!STATUSES.contains(status)) {
            return new ResponseDto(ERROR, "A form's status is Draft, Active or Archived.");
        }
        if (FormStore.ACTIVE.equals(status) && form.get().fields.isEmpty()) {
            return new ResponseDto(ERROR, "Add at least one field before the form takes submissions (Active).");
        }
        this.store.setStatus(tenantId, form.get().formId, status, TenantContext.getAppUserId());
        return new ResponseDto(SUCCESS, FormStore.ACTIVE.equals(status) ? "The form is active: members can fill it in."
            : FormStore.ARCHIVED.equals(status) ? "The form is archived: it takes no more submissions; the ones it has are kept."
            : "The form is a draft again: it takes no submissions until it is active.",
            this.view(this.store.find(tenantId, form.get().formId).orElse(form.get()), true, true));
    }

    /** Null when the job may be linked: none at all, or one of the workspace's own that is not deleted. */
    private String jobRefusal(long tenantId, Long jobId) {
        if (jobId == null) {
            return null;
        }
        Optional<SourceJob> job = this.jobs.findByJobId(jobId);
        if (!job.isPresent() || job.get().getTenantId() == null || job.get().getTenantId() != tenantId
            || job.get().getJobStatus() == Status.Delete) {
            return "The job this form starts was not found in this workspace.";
        }
        return null;
    }

    // ---- views -------------------------------------------------------------------------------------------------

    /** A form as the console reads it; the job behind it only for an administrator, its fields only when asked. */
    Map<String, Object> view(FormStore.Form form, boolean admin, boolean withFields) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("formId", form.formId);
        view.put("name", form.name);
        view.put("description", form.description);
        view.put("status", form.status);
        view.put("version", form.version);
        view.put("fieldCount", form.fields.size());
        if (withFields) {
            view.put("fields", form.fields);
        }
        view.put("startsJob", form.jobId != null);
        if (admin) {
            view.put("jobId", form.jobId);
            view.put("jobName", form.jobId == null ? null : this.jobs.findByJobId(form.jobId)
                .filter(j -> j.getTenantId() != null && j.getTenantId() == form.tenantId).map(SourceJob::getJobName).orElse(null));
            view.put("submissions", form.submissions);
        }
        view.put("dateCreated", text(form.dateCreated));
        view.put("dateUpdated", text(form.dateUpdated == null ? form.dateCreated : form.dateUpdated));
        return view;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // ---- the caller --------------------------------------------------------------------------------------------

    /** The caller's workspace; none (null) for a platform administrator or a token naming no real workspace. */
    static Long workspace() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null || tenantId <= 0 ? null : tenantId;
    }

    static boolean isAdmin() {
        return "TENANT_ADMIN".equals(TenantContext.getUserRole());
    }
}
