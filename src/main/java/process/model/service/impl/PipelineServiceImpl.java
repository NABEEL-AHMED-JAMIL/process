package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.Tenant;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.pojo.SourceTaskType;
import process.util.KafkaTopicPartitionUtil;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.security.TenantOwnership;
import process.util.UserNameResolver;
import process.util.exception.ExceptionUtil;
import process.util.ProcessUtil;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
public class PipelineServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(PipelineServiceImpl.class);

    /** The types the task form can render. Anything else would render as a blank box. */
    private static final Set<String> FIELD_TYPES = new HashSet<>(Arrays.asList(
        "text", "textarea", "number", "url", "select", "checkbox", "date"));

    /** Compiled once: {@link #choiceValues} asks this of every one-line option string. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private final PipelineRepository pipelineRepository;

    private final TenantRepository tenantRepository;

    private final UserNameResolver userNameResolver;

    private final SourceTaskTypeRepository sourceTaskTypeRepository;

    public PipelineServiceImpl(PipelineRepository pipelineRepository, TenantRepository tenantRepository,
        UserNameResolver userNameResolver, SourceTaskTypeRepository sourceTaskTypeRepository) {
        this.pipelineRepository = pipelineRepository;
        this.tenantRepository = tenantRepository;
        this.userNameResolver = userNameResolver;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
    }

    /**
     * The pipelines on one topic, for the task screen: the caller picks a topic first and is
     * then offered only what publishes on it. Scoped like everything else here -- a tenant sees
     * its own rows, a platform admin every tenant's.
     */
    public ResponseDto listForTopic(Long sourceTaskTypeId) {
        if (ProcessUtil.isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "sourceTaskTypeId missing.");
        }
        List<Pipeline> pipelines = this.pipelineRepository
            .findAllBySourceTaskTypeIdAndStatusNotOrderByPipelineNameAsc(sourceTaskTypeId, Status.Delete)
            .stream()
            .filter(p -> TenantContext.isPlatformAdmin()
                || (TenantContext.getTenantId() != null && TenantContext.getTenantId().equals(p.getTenantId())))
            .collect(Collectors.toList());
        this.attachTopics(pipelines);
        return new ResponseDto(SUCCESS, String.format("%d pipeline(s).", pipelines.size()), pipelines);
    }

    /** Names the topic on each row, one lookup for the whole list. */
    private void attachTopics(List<Pipeline> pipelines) {
        java.util.Set<Long> ids = pipelines.stream().map(Pipeline::getSourceTaskTypeId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        java.util.Map<Long, SourceTaskType> topics = new java.util.HashMap<>();
        this.sourceTaskTypeRepository.findAllById(ids).forEach(t -> topics.put(t.getSourceTaskTypeId(), t));
        for (Pipeline p : pipelines) {
            SourceTaskType t = p.getSourceTaskTypeId() == null ? null : topics.get(p.getSourceTaskTypeId());
            if (t == null) continue;
            p.setTopicName(t.getServiceName());
            p.setKafkaTopic(KafkaTopicPartitionUtil.parse(t.getQueueTopicPartition()).map(KafkaTopicPartitionUtil.Parsed::getTopic).orElse(null));
        }
    }

    public ResponseDto listForms() {
        // A tenant sees only its own definitions -- same rule Storage/Kafka Connections apply --
        // a platform admin sees every tenant's. A tenant-role caller with no tenant of its own
        // (should not happen in practice, but fails closed rather than querying with a null and
        // hoping the SQL "= null" never-matches semantics are what the reader expects) sees none.
        List<Pipeline> forms;
        if (TenantContext.isPlatformAdmin()) {
            forms = this.pipelineRepository.findAllByStatusNot(Status.Delete);
        } else if (TenantContext.getTenantId() != null) {
            forms = this.pipelineRepository.findAllByTenantIdAndStatusNotOrderByPipelineKeyDesc(
                TenantContext.getTenantId(), Status.Delete);
        } else {
            forms = new ArrayList<>();
        }
        // One lookup for the whole list rather than one per row.
        java.util.Map<Long, String> authors = this.userNameResolver.namesFor(
            forms.stream().map(Pipeline::getCreatedBy).collect(java.util.stream.Collectors.toList()));
        forms.forEach(f -> f.setCreatedByName(authors.get(f.getCreatedBy())));
        this.attachTopics(forms);
        return new ResponseDto(SUCCESS, String.format("%d pipeline(s).", forms.size()), forms);
    }

    /**
     * The form a task screen should show for this pipeline, or nothing if none is defined.
     *
     * A platform admin carries no tenant of its own to match a form's {@code tenant_id} against
     * -- the same situation {@link #saveForm} handles by filing a new form under the seeded
     * default tenant rather than leaving it ownerless. Reading is more forgiving than writing:
     * rather than refuse a form that plainly exists just because the caller has no tenant to
     * compare it to, a platform admin's request matches the pipeline across every tenant's forms.
     * A tenant-scoped caller is unaffected and still sees only its own tenant's row, per
     * {@link PipelineRepository#findAllByPipelineIdAndTenantIdAndStatusNot}'s own contract.
     */
    public ResponseDto formForPipeline(String pipelineId, Long tenantId) {
        if (ProcessUtil.isNull(pipelineId) || pipelineId.trim().isEmpty()) {
            return new ResponseDto(ERROR, "pipelineId missing.");
        }
        String trimmed = pipelineId.trim();
        List<Pipeline> found;
        if (TenantContext.isPlatformAdmin()) {
            /*
             * A platform admin can see every tenant's forms, and more than one tenant can hold a
             * form for the same pipeline id -- the id is the worker's routing key, not a globally
             * unique name. Returning "whichever came back first" out of that set meant the task
             * screen could load a *different tenant's* form for the task being edited, and saving
             * then wrote that form's fields into the task's tags: a Source Task on this pipeline
             * picked up ten fields belonging to another tenant's form, and the derived storage
             * columns were computed from them. So the task's own tenant is asked for first, and
             * the fallback is ordered rather than arbitrary.
             */
            List<Pipeline> matching = this.pipelineRepository.findAllByStatusNot(Status.Delete)
                .stream()
                .filter(f -> trimmed.equals(f.getPipelineId()))
                .sorted(Comparator.comparing(Pipeline::getPipelineKey))
                .collect(Collectors.toList());
            found = matching;
            if (tenantId != null) {
                List<Pipeline> owned = matching.stream()
                    .filter(f -> tenantId.equals(f.getTenantId()))
                    .collect(Collectors.toList());
                if (!owned.isEmpty()) {
                    found = owned;
                }
            }
        } else {
            found = this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(
                trimmed, TenantContext.getTenantId(), Status.Delete);
        }
        if (found.isEmpty()) {
            // Not an error: most pipelines have no form, and the task screen falls back to tags.
            return new ResponseDto(SUCCESS, "No pipeline is defined with this id.", null);
        }
        this.attachTopics(found);
        return new ResponseDto(SUCCESS, "Pipeline found.", found.get(0));
    }

    @Transactional
    public ResponseDto saveForm(Pipeline submitted) {
        String problem = validate(submitted);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }

        Pipeline target;
        if (submitted.getPipelineKey() != null) {
            Optional<Pipeline> existing = this.pipelineRepository
                .findByPipelineKeyAndStatusNot(submitted.getPipelineKey(), Status.Delete);
            if (!existing.isPresent()) {
                return new ResponseDto(ERROR, "That pipeline no longer exists.");
            }
            target = existing.get();
            if (!isOwnedByCaller(target)) {
                return new ResponseDto(ERROR, "That pipeline belongs to another tenant.");
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
                        "No default tenant is configured to own this pipeline. Sign in as a tenant to create one.");
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
            List<Pipeline> clash = this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(
                submitted.getPipelineId().trim(), ownerTenantId, Status.Delete);
            if (!clash.isEmpty()) {
                return new ResponseDto(ERROR, String.format(
                    "Pipeline %s already exists. Edit that one instead of adding a second.",
                    submitted.getPipelineId().trim()));
            }
            target = new Pipeline();
            target.setTenantId(ownerTenantId);
            target.setCreatedBy(TenantContext.getAppUserId());
        }

        /*
         * The topic is required and has to be one the pipeline's own workspace can see: a
         * pipeline on another tenant's topic would publish into a cluster that tenant owns.
         */
        Optional<SourceTaskType> topic = this.sourceTaskTypeRepository.findById(submitted.getSourceTaskTypeId());
        if (!topic.isPresent() || topic.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "That topic no longer exists.");
        }
        Long topicTenant = topic.get().getTenantId();
        if (topicTenant != null && !topicTenant.equals(target.getTenantId())) {
            return new ResponseDto(ERROR, "That topic belongs to another workspace.");
        }
        target.setSourceTaskTypeId(topic.get().getSourceTaskTypeId());

        target.setPipelineId(submitted.getPipelineId().trim());
        target.setPipelineName(submitted.getPipelineName().trim());
        target.setDescription(submitted.getDescription());
        target.setStatus(submitted.getStatus() == null ? Status.Active : submitted.getStatus());

        // Replaced wholesale rather than merged: the client sends the field list it wants, and
        // reconciling by id would leave a removed field behind whenever the client forgot one.
        target.getFields().clear();
        int position = 0;
        for (PipelineField field : submitted.getFields()) {
            PipelineField copy = new PipelineField();
            copy.setPipeline(target);
            copy.setTagKey(field.getTagKey().trim());
            copy.setTagParent(blankToNull(field.getTagParent()));
            copy.setLabel(field.getLabel().trim());
            copy.setFieldType(FIELD_TYPES.contains(safe(field.getFieldType())) ? field.getFieldType() : "text");
            copy.setRequired(field.isRequired());
            copy.setDefaultValue(field.getDefaultValue());
            copy.setHelpText(field.getHelpText());
            // Stored as sent, still. The choices are one per line with an optional first '=' set
            // splitting the stored value from the displayed label (see choiceValues); validate()
            // has already had its say about them, and rewriting them here would only give the
            // server a second opinion on a format the task screen is the one that has to read.
            copy.setFieldOptions(field.getFieldOptions());
            copy.setPosition(position++);
            target.getFields().add(copy);
        }

        try {
            Pipeline saved = this.pipelineRepository.save(target);
            return new ResponseDto(SUCCESS,
                String.format("\"%s\" saved with %d field(s).", saved.getPipelineName(), saved.getFields().size()),
                saved);
        } catch (DataIntegrityViolationException ex) {
            // The check above catches this in practice; this remains for two saves racing.
            logger.warn("Duplicate pipeline {}", submitted.getPipelineId());
            return new ResponseDto(ERROR,
                "That pipeline id already exists. Edit that one instead of adding a second.");
        } catch (Exception ex) {
            logger.error("Could not save pipeline {}", submitted.getPipelineId(), ex);
            return new ResponseDto(ERROR, "That pipeline could not be saved: " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Transactional
    public ResponseDto deleteForm(Long pipelineKey) {
        if (ProcessUtil.isNull(pipelineKey)) {
            return new ResponseDto(ERROR, "pipelineKey missing.");
        }
        Optional<Pipeline> existing = this.pipelineRepository
            .findByPipelineKeyAndStatusNot(pipelineKey, Status.Delete);
        if (!existing.isPresent()) {
            return new ResponseDto(ERROR, "That pipeline no longer exists.");
        }
        if (!isOwnedByCaller(existing.get())) {
            return new ResponseDto(ERROR, "That pipeline belongs to another tenant.");
        }
        // Soft delete, and safe regardless: tasks keep their own tags, so removing a definition
        // changes how new tasks are configured and nothing about existing ones.
        existing.get().setStatus(Status.Delete);
        this.pipelineRepository.save(existing.get());
        return new ResponseDto(SUCCESS, "Pipeline deleted. Tasks already configured with it are unaffected.");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Package-private and static so the rules can be tested without a database behind them. */
    static String validate(Pipeline form) {
        if (form == null) return "Nothing to save.";
        if (isBlank(form.getPipelineId())) return "Give the pipeline its id -- the one the worker routes on.";
        if (isBlank(form.getPipelineName())) return "Give the pipeline a name.";
        if (form.getSourceTaskTypeId() == null) return "Choose the topic this pipeline publishes on.";
        if (form.getFields() == null || form.getFields().isEmpty()) {
            return "A pipeline needs at least one field.";
        }
        Set<String> keys = new HashSet<>();
        for (PipelineField field : form.getFields()) {
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
        for (PipelineField field : form.getFields()) {
            String parent = blankToNull(field.getTagParent());
            if (parent != null && !keys.contains(parent)) {
                return String.format("\"%s\" nests under <%s>, which no field creates.",
                    field.getLabel().trim(), parent);
            }
        }
        return validateSelectChoices(form);
    }

    /**
     * What a dropdown's choices have to satisfy.
     *
     * Nothing checked field_options at all before this. It was copied into the row unexamined,
     * which was defensible while the column was only ever read to paint a list -- and stopped
     * being defensible once a choice carries a stored value the worker acts on. Each rule below
     * describes a form that saves without complaint and then misbehaves somewhere else:
     *
     * A select with no choices gives the operator a dropdown holding nothing but "None", and when
     * the field is also required and carries no default, that task can never be made valid --
     * with "Check the highlighted fields." as the only explanation anyone gets.
     *
     * Two choices with the same stored value make the second unreachable, since the browser
     * matches on the first.
     *
     * And a default that is not one of the values is the one that hides: the task screen seeds
     * the control with it, the required check passes because a non-empty string is non-empty, no
     * option matches so the control renders blank, and an untouched save still writes that
     * unseen value into the payload. Rejecting it here is what makes renaming a choice an error
     * the author is told about rather than a silent break in every task on the pipeline.
     */
    private static String validateSelectChoices(Pipeline form) {
        for (PipelineField field : form.getFields()) {
            if (!"select".equals(safe(field.getFieldType()))) {
                continue;
            }
            String name = isBlank(field.getLabel()) ? safe(field.getTagKey()) : field.getLabel().trim();
            List<String> values = choiceValues(field.getFieldOptions());
            if (values.isEmpty()) {
                return String.format(
                    "The \"%s\" dropdown has no choices. Add at least one, or change its type.", name);
            }
            Set<String> seen = new HashSet<>();
            for (String value : values) {
                if (!seen.add(value)) {
                    return String.format(
                        "\"%s\" offers \"%s\" twice. Each choice needs its own value.", name, value);
                }
            }
            String fallback = safe(field.getDefaultValue());
            if (!fallback.isEmpty() && !seen.contains(fallback)) {
                return String.format("\"%s\" defaults to \"%s\", which is not one of its choices. "
                    + "A task would open on a blank dropdown and still send that value.", name, fallback);
            }
        }
        return null;
    }

    /**
     * The stored values of a select's choices, in the order the author put them.
     *
     * This has to agree, line for line, with parseFieldChoices in task-form-dialog.ts -- it is
     * the same format read twice, and a server that disagreed with the browser about what a
     * choice is would reject forms the author can see are fine, or accept ones the author cannot
     * use. Only the values are wanted here; the labels are the browser's business.
     *
     * The format is one choice per line, with an optional first '=' separating the stored value
     * from the displayed label. A line without one is the legacy case that predates labels
     * entirely, and is its own value -- unchanged, because every task saved before this reads its
     * answer back by matching that exact string.
     *
     * The comma branch is a read-side tolerance for what the ETL demo seeder wrote (nine select
     * fields as "records,lines" and the like), which a newline-only split turned into one choice
     * that no default ever matched. It is guarded narrowly -- one line, no '=', no whitespace
     * anywhere on it -- so it cannot swallow a real single choice such as "Doe, John".
     */
    private static List<String> choiceValues(String fieldOptions) {
        List<String> values = new ArrayList<>();
        if (fieldOptions == null) {
            return values;
        }
        List<String> lines = new ArrayList<>();
        for (String line : fieldOptions.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        if (lines.size() == 1 && lines.get(0).indexOf('=') < 0 && lines.get(0).indexOf(',') >= 0
                && !WHITESPACE.matcher(lines.get(0)).find()) {
            lines = new ArrayList<>();
            for (String part : fieldOptions.trim().split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }
        for (String line : lines) {
            int separator = line.indexOf('=');
            // A separator at position 0 would leave no value at all, so that line is legacy too.
            values.add(separator <= 0 ? line : line.substring(0, separator).trim());
        }
        return values;
    }

    private boolean isOwnedByCaller(Pipeline form) {
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
