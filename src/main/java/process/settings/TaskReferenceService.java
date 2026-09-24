package process.settings;

import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.pojo.TaskReference;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.util.UserNameResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Home pages and task groups, the typed screens that replace the generic Lookups screen's two families (MIG-167, D1).
 *
 * What lookup_data could not say is now enforced: a name is unique per workspace and kind (D3) rather than across the
 * platform; a home page is an http(s) URL, since the dispatcher hands it to the worker as where people land; a row a
 * live task uses cannot be deleted (MIG-165); and another workspace's row answers exactly as one that does not exist.
 */
@Service
public class TaskReferenceService {

    private static final int MAX_NAME = 255;
    private static final int MAX_VALUE = 2048;

    private static final Logger logger = LoggerFactory.getLogger(TaskReferenceService.class);

    private final TaskReferenceRepository references;
    private final SourceTaskRepository tasks;
    private final IdentityPort identity;
    private final UserNameResolver names;

    public TaskReferenceService(TaskReferenceRepository references, SourceTaskRepository tasks, IdentityPort identity,
        UserNameResolver names) {
        this.references = references;
        this.tasks = tasks;
        this.identity = identity;
        this.names = names;
    }

    public ResponseDto list(String kind, Long requestedTenantId) {
        if (!isKind(kind)) {
            return new ResponseDto(ERROR, "The kind is HOME_PAGE or TASK_GROUP.");
        }
        Long tenantId = ActingWorkspace.forList(requestedTenantId);
        List<TaskReference> rows = tenantId == null
            ? this.references.findByKindOrderByTenantIdAscNameAsc(kind)
            : this.references.findByTenantIdAndKindOrderByNameAsc(tenantId, kind);
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.answers(rows));
    }

    @Transactional
    public ResponseDto add(TaskReferenceDto request) {
        String kind = request.getKind();
        if (!isKind(kind)) {
            return new ResponseDto(ERROR, "The kind is HOME_PAGE or TASK_GROUP.");
        }
        String refusal = refuseFields(kind, request);
        if (refusal != null) {
            return new ResponseDto(ERROR, refusal);
        }
        Long[] tenant = new Long[1];
        String workspaceRefusal = ActingWorkspace.resolve(this.identity, request.getTenantId(), id -> tenant[0] = id);
        if (workspaceRefusal != null) {
            return new ResponseDto(ERROR, workspaceRefusal);
        }
        String name = request.getName().trim();
        if (this.references.findByTenantIdAndKindAndName(tenant[0], kind, name).isPresent()) {
            return new ResponseDto(ERROR, duplicate(kind, name));
        }
        TaskReference row = new TaskReference();
        row.setTenantId(tenant[0]);
        row.setKind(kind);
        this.fill(row, request);
        TaskReference saved = this.references.save(row);
        logger.info("{} {} added to workspace {}.", noun(kind), saved.getId(), tenant[0]);
        return new ResponseDto(SUCCESS, String.format("%s saved.", name), this.answers(one(saved)).get(0));
    }

    @Transactional
    public ResponseDto update(TaskReferenceDto request) {
        Optional<TaskReference> found = request.getId() == null ? Optional.empty() : this.references.findById(request.getId());
        if (!found.isPresent() || !ActingWorkspace.mayTouch(found.get().getTenantId())) {
            return new ResponseDto(ERROR, String.format("Entry %s not found.", request.getId()));
        }
        TaskReference row = found.get();
        if (request.getKind() != null && !request.getKind().equals(row.getKind())) {
            return new ResponseDto(ERROR, "The kind of an entry cannot change. Add a new one instead.");
        }
        String refusal = refuseFields(row.getKind(), request);
        if (refusal != null) {
            return new ResponseDto(ERROR, refusal);
        }
        String name = request.getName().trim();
        Optional<TaskReference> sameName = this.references.findByTenantIdAndKindAndName(row.getTenantId(), row.getKind(), name);
        if (sameName.isPresent() && !sameName.get().getId().equals(row.getId())) {
            return new ResponseDto(ERROR, duplicate(row.getKind(), name));
        }
        this.fill(row, request);
        TaskReference saved = this.references.save(row);
        logger.info("{} {} of workspace {} updated.", noun(row.getKind()), row.getId(), row.getTenantId());
        return new ResponseDto(SUCCESS, String.format("%s saved.", name), this.answers(one(saved)).get(0));
    }

    @Transactional
    public ResponseDto delete(Long id) {
        Optional<TaskReference> found = id == null ? Optional.empty() : this.references.findById(id);
        if (!found.isPresent() || !ActingWorkspace.mayTouch(found.get().getTenantId())) {
            return new ResponseDto(ERROR, String.format("Entry %s not found.", id));
        }
        TaskReference row = found.get();
        long using = this.tasks.countLiveTasksReferencing(row.getId());
        if (using > 0) {
            return new ResponseDto(ERROR, String.format("%d task%s still use%s \"%s\". Change %s first.", using,
                using == 1 ? "" : "s", using == 1 ? "s" : "", row.getName(), using == 1 ? "that task" : "those tasks"));
        }
        this.references.delete(row);
        logger.info("{} {} deleted from workspace {}.", noun(row.getKind()), row.getId(), row.getTenantId());
        return new ResponseDto(SUCCESS, String.format("%s deleted.", row.getName()));
    }

    private void fill(TaskReference row, TaskReferenceDto request) {
        row.setName(request.getName().trim());
        row.setValue(blankToNull(request.getValue()));
        row.setDescription(blankToNull(request.getDescription()));
    }

    private static String refuseFields(String kind, TaskReferenceDto request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            return "A name is required.";
        }
        if (name.length() > MAX_NAME) {
            return String.format("A name is at most %d characters.", MAX_NAME);
        }
        String value = blankToNull(request.getValue());
        if (TaskReference.HOME_PAGE.equals(kind) && (value == null || !isHttpUrl(value))) {
            return "A home page is an http:// or https:// address.";
        }
        if (value != null && value.length() > MAX_VALUE) {
            return String.format("A value is at most %d characters.", MAX_VALUE);
        }
        if (request.getDescription() != null && request.getDescription().length() > 255) {
            return "A description is at most 255 characters.";
        }
        return null;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception notAUri) {
            return false;
        }
    }

    private List<TaskReferenceDto> answers(List<TaskReference> rows) {
        Set<Long> people = new HashSet<>();
        for (TaskReference row : rows) {
            people.add(row.getCreatedBy());
            people.add(row.getUpdatedBy());
        }
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        List<TaskReferenceDto> answers = new ArrayList<>();
        for (TaskReference row : rows) {
            TaskReferenceDto dto = new TaskReferenceDto();
            dto.setId(row.getId());
            dto.setTenantId(row.getTenantId());
            dto.setKind(row.getKind());
            dto.setName(row.getName());
            dto.setValue(row.getValue());
            dto.setDescription(row.getDescription());
            dto.setCreatedAt(ActingWorkspace.iso(row.getCreatedAt()));
            dto.setCreatedByName(row.getCreatedBy() == null ? null : byId.get(row.getCreatedBy()));
            dto.setUpdatedAt(ActingWorkspace.iso(row.getUpdatedAt()));
            dto.setUpdatedByName(row.getUpdatedBy() == null ? null : byId.get(row.getUpdatedBy()));
            dto.setUsedByTasks(row.getId() == null ? 0L : this.tasks.countLiveTasksReferencing(row.getId()));
            answers.add(dto);
        }
        return answers;
    }

    private static boolean isKind(String kind) {
        return TaskReference.HOME_PAGE.equals(kind) || TaskReference.TASK_GROUP.equals(kind);
    }

    private static String noun(String kind) {
        return TaskReference.HOME_PAGE.equals(kind) ? "Home page" : "Task group";
    }

    private static String duplicate(String kind, String name) {
        return String.format("A %s named %s already exists in this workspace.",
            TaskReference.HOME_PAGE.equals(kind) ? "home page" : "task group", name);
    }

    private static String blankToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static List<TaskReference> one(TaskReference row) {
        List<TaskReference> list = new ArrayList<>();
        list.add(row);
        return list;
    }
}
