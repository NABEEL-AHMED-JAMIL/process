package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.TaskForm;
import process.model.pojo.TaskFormField;
import process.model.pojo.Tenant;
import process.model.repository.TaskFormRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.security.TenantOwnership;
import process.util.UserNameResolver;
import process.util.exception.ExceptionUtil;
import process.util.ProcessUtil;

import java.util.*;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Form definitions: what a pipeline's payload looks like, so tasks can be filled in.
 *
 * Nothing here writes a task. A definition is metadata about tags; the task screen uses it to
 * present a form and then saves ordinary tags, which means a form can be changed or removed
 * without altering a single existing task.
 *
 * @author Nabeel Ahmed
 */
@Service
public class TaskFormServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(TaskFormServiceImpl.class);

    /** The types the task form can render. Anything else would render as a blank box. */
    private static final Set<String> FIELD_TYPES = new HashSet<>(Arrays.asList(
        "text", "textarea", "number", "url", "select", "checkbox", "date"));

    private final TaskFormRepository taskFormRepository;

    private final TenantRepository tenantRepository;

    private final UserNameResolver userNameResolver;

    public TaskFormServiceImpl(TaskFormRepository taskFormRepository, TenantRepository tenantRepository,
        UserNameResolver userNameResolver) {
        this.taskFormRepository = taskFormRepository;
        this.tenantRepository = tenantRepository;
        this.userNameResolver = userNameResolver;
    }

    public ResponseDto listForms() {
        // A tenant sees only its own definitions -- same rule Storage/Kafka Connections apply --
        // a platform admin sees every tenant's. A tenant-role caller with no tenant of its own
        // (should not happen in practice, but fails closed rather than querying with a null and
        // hoping the SQL "= null" never-matches semantics are what the reader expects) sees none.
        List<TaskForm> forms;
        if (TenantContext.isPlatformAdmin()) {
            forms = this.taskFormRepository.findAllByFormStatusNot(Status.Delete);
        } else if (TenantContext.getTenantId() != null) {
            forms = this.taskFormRepository.findAllByTenantIdAndFormStatusNotOrderByTaskFormIdDesc(
                TenantContext.getTenantId(), Status.Delete);
        } else {
            forms = new ArrayList<>();
        }
        // One lookup for the whole list rather than one per row.
        java.util.Map<Long, String> authors = this.userNameResolver.namesFor(
            forms.stream().map(TaskForm::getCreatedBy).collect(java.util.stream.Collectors.toList()));
        forms.forEach(f -> f.setCreatedByName(authors.get(f.getCreatedBy())));
        return new ResponseDto(SUCCESS, String.format("%d form(s).", forms.size()), forms);
    }

    /** The form a task screen should show for this pipeline, or nothing if none is defined. */
    public ResponseDto formForPipeline(String pipelineId) {
        if (ProcessUtil.isNull(pipelineId) || pipelineId.trim().isEmpty()) {
            return new ResponseDto(ERROR, "pipelineId missing.");
        }
        List<TaskForm> found = this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(
            pipelineId.trim(), TenantContext.getTenantId(), Status.Delete);
        if (found.isEmpty()) {
            // Not an error: most pipelines have no form, and the task screen falls back to tags.
            return new ResponseDto(SUCCESS, "No form is defined for this pipeline.", null);
        }
        return new ResponseDto(SUCCESS, "Form found.", found.get(0));
    }

    @Transactional
    public ResponseDto saveForm(TaskForm submitted) {
        String problem = validate(submitted);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }

        TaskForm target;
        if (submitted.getTaskFormId() != null) {
            Optional<TaskForm> existing = this.taskFormRepository
                .findByTaskFormIdAndFormStatusNot(submitted.getTaskFormId(), Status.Delete);
            if (!existing.isPresent()) {
                return new ResponseDto(ERROR, "That form no longer exists.");
            }
            target = existing.get();
            if (!isOwnedByCaller(target)) {
                return new ResponseDto(ERROR, "That form belongs to another tenant.");
            }
        } else {
            // A new form always belongs to one tenant -- same rule as a new storage or Kafka
            // connection. There is no dispatch mechanism here that would give a platform-wide
            // form a purpose the way KafkaConnectionResolver's default profile has one: a
            // pipeline with no form for the caller's tenant just falls back to plain tags (see
            // formForPipeline), so a null-tenant row would only ever be dead weight -- visible
            // to nobody's task screen. A platform admin's new form is therefore filed under the
            // seeded "default" tenant instead (the same tenant TenantSeedService backfills
            // pre-tenancy rows into) rather than left tenantless: only that tenant's users can
            // use it, and it stays reachable for anyone signed in as it to edit or delete.
            Long ownerTenantId = TenantContext.getTenantId();
            if (ownerTenantId == null) {
                Optional<Tenant> defaultTenant = this.tenantRepository
                    .findByTenantCode(TenantSeedService.DEFAULT_TENANT_CODE);
                if (!defaultTenant.isPresent()) {
                    return new ResponseDto(ERROR,
                        "No default tenant is configured to own this form. Sign in as a tenant to create one.");
                }
                ownerTenantId = defaultTenant.get().getTenantId();
            }
            /*
             * Checked here rather than caught from the unique index.
             *
             * This method is transactional, so a constraint violation surfaces at commit --
             * after the catch block below has already returned. Relying on it produced the
             * generic "internal error" for what is an ordinary, explainable situation.
             */
            List<TaskForm> clash = this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(
                submitted.getPipelineId().trim(), ownerTenantId, Status.Delete);
            if (!clash.isEmpty()) {
                return new ResponseDto(ERROR, String.format(
                    "A form already exists for pipeline %s. Edit that one instead of adding a second.",
                    submitted.getPipelineId().trim()));
            }
            target = new TaskForm();
            target.setTenantId(ownerTenantId);
            target.setCreatedBy(TenantContext.getAppUserId());
        }

        target.setPipelineId(submitted.getPipelineId().trim());
        target.setFormName(submitted.getFormName().trim());
        target.setDescription(submitted.getDescription());
        target.setFormStatus(submitted.getFormStatus() == null ? Status.Active : submitted.getFormStatus());

        // Replaced wholesale rather than merged: the client sends the field list it wants, and
        // reconciling by id would leave a removed field behind whenever the client forgot one.
        target.getFields().clear();
        int position = 0;
        for (TaskFormField field : submitted.getFields()) {
            TaskFormField copy = new TaskFormField();
            copy.setTaskForm(target);
            copy.setTagKey(field.getTagKey().trim());
            copy.setTagParent(blankToNull(field.getTagParent()));
            copy.setLabel(field.getLabel().trim());
            copy.setFieldType(FIELD_TYPES.contains(safe(field.getFieldType())) ? field.getFieldType() : "text");
            copy.setRequired(field.isRequired());
            copy.setDefaultValue(field.getDefaultValue());
            copy.setHelpText(field.getHelpText());
            copy.setFieldOptions(field.getFieldOptions());
            copy.setPosition(position++);
            target.getFields().add(copy);
        }

        try {
            TaskForm saved = this.taskFormRepository.save(target);
            return new ResponseDto(SUCCESS,
                String.format("\"%s\" saved with %d field(s).", saved.getFormName(), saved.getFields().size()),
                saved);
        } catch (DataIntegrityViolationException ex) {
            // The check above catches this in practice; this remains for two saves racing.
            logger.warn("Duplicate task form for pipeline {}", submitted.getPipelineId());
            return new ResponseDto(ERROR,
                "A form already exists for that pipeline. Edit that one instead of adding a second.");
        } catch (Exception ex) {
            logger.error("Could not save task form for pipeline {}", submitted.getPipelineId(), ex);
            return new ResponseDto(ERROR, "That form could not be saved: " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Transactional
    public ResponseDto deleteForm(Long taskFormId) {
        if (ProcessUtil.isNull(taskFormId)) {
            return new ResponseDto(ERROR, "taskFormId missing.");
        }
        Optional<TaskForm> existing = this.taskFormRepository
            .findByTaskFormIdAndFormStatusNot(taskFormId, Status.Delete);
        if (!existing.isPresent()) {
            return new ResponseDto(ERROR, "That form no longer exists.");
        }
        if (!isOwnedByCaller(existing.get())) {
            return new ResponseDto(ERROR, "That form belongs to another tenant.");
        }
        // Soft delete, and safe regardless: tasks keep their own tags, so removing a definition
        // changes how new tasks are configured and nothing about existing ones.
        existing.get().setFormStatus(Status.Delete);
        this.taskFormRepository.save(existing.get());
        return new ResponseDto(SUCCESS, "Form deleted. Tasks already configured with it are unaffected.");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Package-private and static so the rules can be tested without a database behind them. */
    static String validate(TaskForm form) {
        if (form == null) return "Nothing to save.";
        if (isBlank(form.getPipelineId())) return "Choose the pipeline this form is for.";
        if (isBlank(form.getFormName())) return "Give the form a name.";
        if (form.getFields() == null || form.getFields().isEmpty()) {
            return "A form needs at least one field.";
        }
        Set<String> keys = new HashSet<>();
        for (TaskFormField field : form.getFields()) {
            if (isBlank(field.getTagKey())) return "Every field needs an XML tag.";
            if (isBlank(field.getLabel())) return String.format(
                "The field for \"%s\" needs a label.", field.getTagKey().trim());
            // Two fields writing one tag means the second silently wins.
            if (!keys.add(field.getTagKey().trim())) {
                return String.format("Two fields both write <%s>. Each tag can appear once.",
                    field.getTagKey().trim());
            }
        }
        // A parent has to exist, or the generated document loses the field.
        for (TaskFormField field : form.getFields()) {
            String parent = blankToNull(field.getTagParent());
            if (parent != null && !keys.contains(parent)) {
                return String.format("\"%s\" nests under <%s>, which no field creates.",
                    field.getLabel().trim(), parent);
            }
        }
        return null;
    }

    private boolean isOwnedByCaller(TaskForm form) {
        // Delegates rather than re-deriving the rule locally -- TenantOwnership exists precisely
        // because every service used to grow its own private copy of this check.
        return TenantOwnership.isOwnedByCaller(form.getTenantId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
