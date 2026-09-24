package process.model.service.impl;

import process.settings.ConfigReferences;
import org.apache.poi.ss.usermodel.Row;
import process.util.BusinessTime;
import process.util.UserNameResolver;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.*;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.pojo.SourceTaskPayload;
import process.util.XmlOutTagInfoUtil;
import java.util.List;
import process.model.pojo.TaskReference;
import process.model.pojo.SourceTaskType;
import process.model.pojo.SourceTask;
import process.model.projection.SourceTaskProjection;
import process.model.repository.TaskReferenceRepository;
import process.settings.TaskConfigRules;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.SourceTaskRepository;
import process.identity.IdentityPort;
import process.model.service.SourceTaskService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.PagingUtil;
import process.util.PlatformDatabases;
import process.util.ProcessUtil;
import process.util.EnumConverter;
import process.util.TaskPayloadLocationUtil;
import process.util.excel.BulkExcel;
import process.util.validation.SourceTaskValidation;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.ERROR;
import java.util.function.Consumer;
import process.notifications.Notices;
import process.notifications.NotificationPort;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class SourceTaskServiceImpl implements SourceTaskService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskServiceImpl.class);

    private final BulkExcel bulkExcel;
    private final QueryService queryService;
    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final TenantFilterHelper tenantFilterHelper;
    private final TaskPayloadLocationUtil taskPayloadLocationUtil;
    private final IdentityPort identity;
    private final NotificationPort notifications;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;

    /**
     * Field-injected and optional: only the home page and group check reads task references, and the constructor is
     * built by hand in several tests. Unwired, an id is still held to existing by the foreign key; wired, as it always
     * is in the application, it is also held to its kind and its workspace.
     */
    @Autowired(required = false)
    private TaskReferenceRepository taskReferenceRepository;

    /**
     * The configuration rules (MIG-167). Optional for the same reason, but fails closed: unwired, a payload that
     * references configuration at all is refused, since whether the entry exists cannot be checked.
     */
    @Autowired(required = false)
    private TaskConfigRules taskConfigRules;

    private static final String HOME_PAGES = TaskReference.HOME_PAGE;

    private static final String TASK_GROUPS = TaskReference.TASK_GROUP;


    public SourceTaskServiceImpl(BulkExcel bulkExcel,
        QueryService queryService,
        SourceJobRepository sourceJobRepository,
        SourceTaskRepository sourceTaskRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        TenantFilterHelper tenantFilterHelper,
        TaskPayloadLocationUtil taskPayloadLocationUtil,
        IdentityPort identity,
        NotificationPort notifications,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.bulkExcel = bulkExcel;
        this.queryService = queryService;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.taskPayloadLocationUtil = taskPayloadLocationUtil;
        this.identity = identity;
        this.notifications = notifications;
    }

    /**
     * A home page or group id as the caller sent it, as the task_reference row it has to be (MIG-165, MIG-167).
     *
     * The columns are bigint foreign keys (to lookup_data since V70.3, to task_reference since V141). Nothing checked
     * them before: any string was stored, a non-number simply never resolved, and an id from another workspace was
     * accepted -- and a home page is resolved to its URL at dispatch, so that put one workspace's URL into another's
     * job payload. Blank is "none". Anything else must be a row of the named kind that belongs to the task's
     * workspace; every other case gets the same refusal, so the answer does not confirm that another workspace's id
     * exists.
     */
    private String refuseReference(String raw, String kind, String what, Long taskTenantId, Consumer<Long> onResolved) {
        if (raw == null || raw.trim().isEmpty()) {
            onResolved.accept(null);
            return null;
        }
        Long id = ProcessUtil.parseLongOrNull(raw);
        String refusal = String.format("%s %s is not one of this workspace's %s.", what, raw.trim(),
            HOME_PAGES.equals(kind) ? "home pages" : "task groups");
        if (id == null) {
            return refusal;
        }
        if (this.taskReferenceRepository != null) {
            Optional<TaskReference> row = this.taskReferenceRepository.findById(id);
            boolean fits = row.isPresent() && kind.equals(row.get().getKind())
                && row.get().getTenantId() != null && row.get().getTenantId().equals(taskTenantId);
            if (!fits) {
                return refusal;
            }
        }
        onResolved.accept(id);
        return null;
    }

    /** The configuration rules for a payload saved into this workspace (MIG-167), failing closed when unwired. */
    private Optional<String> refuseConfiguration(String payload, Long taskTenantId) {
        if (this.taskConfigRules != null) {
            return this.taskConfigRules.refusal(payload, taskTenantId);
        }
        Optional<String> refused = TaskConfigRules.payloadRefusal(payload);
        if (refused.isPresent() || ConfigReferences.in(payload).isEmpty()) {
            return refused;
        }
        return Optional.of("Configuration references cannot be checked right now; the task was not saved.");
    }

    private static String idText(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private ResponseDto resolveTenantIdForCreate(Long requestedTenantId, Consumer<Long> onResolved) {
        if (!TenantContext.isPlatformAdmin()) {
            onResolved.accept(TenantContext.getTenantId());
            return null;
        }
        if (ProcessUtil.isNull(requestedTenantId)) {
            return new ResponseDto(ERROR, "Tenant is required when creating a source task as a platform administrator.");
        }
        if (!IdentityPort.live(this.identity, requestedTenantId).isPresent()) {
            return new ResponseDto(ERROR, "Selected tenant not found.");
        }
        onResolved.accept(requestedTenantId);
        return null;
    }

    private void applyDerivedLocation(SourceTask sourceTask) {
        TaskPayloadLocationUtil.Location location = this.taskPayloadLocationUtil.extract(sourceTask.getTaskPayload());
        sourceTask.setBucket(location.getBucket());
        sourceTask.setInputFolder(location.getInputFolder());
        sourceTask.setOutputFolder(location.getOutputFolder());
    }

    private boolean isOwnedByCaller(SourceTask sourceTask) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return sourceTask != null && Objects.equals(sourceTask.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Delete is a soft delete -- the row stays in source_task and findById keeps returning it,
     * because findById knows nothing about task_status. Every list a user can reach a task from
     * does know: listSourceTaskQuery selects only ('Active', 'Inactive'), and
     * downloadListSourceTask and fetchAllLinkSourceTaskWithSourceTaskTypeId both exclude
     * 'Delete'. So a task the list said was gone still opened in the editor when its id was
     * known, and saving from that screen wrote it back to Active -- while the jobs
     * deleteSourceTask had already cascaded to Delete stayed deleted, leaving a live task
     * pointing at a set of dead jobs that no screen shows. A deleted task has to read as absent
     * on the by-id paths too.
     */
    private boolean isDeleted(SourceTask sourceTask) {
        return sourceTask != null && Status.Delete.equals(sourceTask.getTaskStatus());
    }

    /**
     * A task type with no tenant is a platform-wide one and everybody may link to it; a
     * tenant-owned one only belongs to its own tenant. The link is not cosmetic -- the type
     * carries the Kafka topic and connection profile the job later publishes with, so binding
     * a task to somebody else's type would hand over their topology and their broker.
     */
    private boolean isSourceTaskTypeVisibleToCaller(SourceTaskType sourceTaskType) {
        if (TenantContext.isPlatformAdmin() || ProcessUtil.isNull(sourceTaskType.getTenantId())) {
            return true;
        }
        return Objects.equals(sourceTaskType.getTenantId(), TenantContext.getTenantId());
    }

    private final String ListSourceTask = "ListSourceTask";
    private final String SOURCE_TASK_HEADER[] = {
        "Task Id", "Task Name", "Task Payload",
        "Task Status", "PipelineId", "HomePage",
        "ServiceName", "QueueTopicPartition",
    };
    private final String UPLOAD_SOURCE_TASK_HEADER[] = {
        "TaskTypeId", "Task Name", "Task Payload", "PipelineId", "HomePage"
    };

    @Override
    @Transactional
    public ResponseDto addSourceTask(SourceTaskDto sourceTaskDto) throws Exception {
        if (ProcessUtil.isNull(sourceTaskDto.getTaskName())) {
            return new ResponseDto(ERROR, "SourceTask taskName missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getTaskPayload())) {
            return new ResponseDto(ERROR, "SourceTask taskPayload missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getSourceTaskType())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskType missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskTypeId missing.");
        } else if (Status.Delete.equals(sourceTaskDto.getTaskStatus())) {
            // Delete is the tombstone deleteSourceTask writes, not a state to create in: a task
            // saved that way is invisible to every list and picker the moment it exists, and the
            // caller is told it was saved.
            return new ResponseDto(ERROR, "SourceTask cannot be created as Delete -- create it Active or Inactive.");
        }
        Optional<String> platformDatabase = PlatformDatabases.refusal(sourceTaskDto.getTaskPayload());
        if (platformDatabase.isPresent()) {
            return new ResponseDto(ERROR, platformDatabase.get());
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            sourceTaskDto.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        // Same wording either way, so a refusal does not confirm the id exists.
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeVisibleToCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, "Provided sourceTaskTypeId not found.");
        }
        SourceTask sourceTask = new SourceTask();
        ResponseDto tenantError = this.resolveTenantIdForCreate(sourceTaskDto.getTenantId(), sourceTask::setTenantId);
        if (tenantError != null) {
            return tenantError;
        }
        String referenceError = this.refuseReference(sourceTaskDto.getHomePageId(), HOME_PAGES, "Home page",
            sourceTask.getTenantId(), sourceTask::setHomePageId);
        if (referenceError == null) {
            referenceError = this.refuseReference(sourceTaskDto.getGroupId(), TASK_GROUPS, "Group",
                sourceTask.getTenantId(), sourceTask::setGroupId);
        }
        if (referenceError != null) {
            return new ResponseDto(ERROR, referenceError);
        }
        Optional<String> configuration = this.refuseConfiguration(sourceTaskDto.getTaskPayload(), sourceTask.getTenantId());
        if (configuration.isPresent()) {
            return new ResponseDto(ERROR, configuration.get());
        }
        sourceTask.setTaskName(sourceTaskDto.getTaskName());
        sourceTask.setTaskPayload(sourceTaskDto.getTaskPayload());
        sourceTask.setPipelineId(sourceTaskDto.getPipelineId());
        /*
         * The caller's choice, not a constant. Every other field here comes from the DTO and this
         * one was hard-coded to Active, so the console's Clone button -- which posts Inactive and
         * then tells the operator "it starts inactive" -- produced a live task. That matters
         * because being Active is what makes a task bindable: updateSourceJob looks the task up
         * with findByTaskDetailIdAndTaskStatus(.., Active) and refuses it otherwise, and the bulk
         * job upload builds its valid-task list the same way. A half-configured copy the operator
         * believed was parked could therefore be attached to live jobs.
         */
        sourceTask.setTaskStatus(!ProcessUtil.isNull(sourceTaskDto.getTaskStatus())
            ? sourceTaskDto.getTaskStatus() : Status.Active);
        sourceTask.setSourceTaskType(sourceTaskType.get());
        this.applyDerivedLocation(sourceTask);
        sourceTask.setSourceTaskPayload(this.tagRowsFor(sourceTaskDto, sourceTask.getTenantId()));
        this.sourceTaskRepository.save(sourceTask);
        return new ResponseDto(SUCCESS, String.format("SourceTask successfully saved with ID %d.", sourceTask.getTaskDetailId()));
    }

    @Override
    @Transactional
    public ResponseDto updateSourceTask(SourceTaskDto sourceTaskDto) throws Exception {
        if (ProcessUtil.isNull(sourceTaskDto.getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceTask taskDetailId missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getTaskName())) {
            return new ResponseDto(ERROR, "SourceTask taskName missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getTaskPayload())) {
            return new ResponseDto(ERROR, "SourceTask taskPayload missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getSourceTaskType())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskType missing.");
        } else if (ProcessUtil.isNull(sourceTaskDto.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskTypeId missing.");
        }
        Optional<String> platformDatabase = PlatformDatabases.refusal(sourceTaskDto.getTaskPayload());
        if (platformDatabase.isPresent()) {
            return new ResponseDto(ERROR, platformDatabase.get());
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            sourceTaskDto.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        // A type the caller cannot see is refused with the wording used for a missing one, so
        // the response is not an oracle for which ids exist in other tenants.
        if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeVisibleToCaller(sourceTaskType.get())) {
            return new ResponseDto(ERROR, "Active the linked sourceTaskType.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(sourceTaskDto.getTaskDetailId());
        // A deleted task is refused with the wording used for one the caller does not own, and
        // for one that never existed. Closing only the read path would still leave an editor
        // tab that was open when the delete landed holding a usable id, and its save would
        // resurrect the row.
        if (sourceTask.isPresent()
            && (!this.isOwnedByCaller(sourceTask.get()) || this.isDeleted(sourceTask.get()))) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskDto.getTaskDetailId()));
        }
        Long[] references = new Long[2];
        if (sourceTask.isPresent()) {
            String referenceError = this.refuseReference(sourceTaskDto.getHomePageId(), HOME_PAGES, "Home page",
                sourceTask.get().getTenantId(), id -> references[0] = id);
            if (referenceError == null) {
                referenceError = this.refuseReference(sourceTaskDto.getGroupId(), TASK_GROUPS, "Group",
                    sourceTask.get().getTenantId(), id -> references[1] = id);
            }
            if (referenceError != null) {
                return new ResponseDto(ERROR, referenceError);
            }
            Optional<String> configuration = this.refuseConfiguration(sourceTaskDto.getTaskPayload(), sourceTask.get().getTenantId());
            if (configuration.isPresent()) {
                return new ResponseDto(ERROR, configuration.get());
            }
        }
        if (sourceTask.isPresent()) {
            if (!ProcessUtil.isNull(sourceTaskDto.getTaskName())) {
                sourceTask.get().setTaskName(sourceTaskDto.getTaskName());
            }
            if (!ProcessUtil.isNull(sourceTaskDto.getTaskPayload())) {
                sourceTask.get().setTaskPayload(sourceTaskDto.getTaskPayload());
                this.applyDerivedLocation(sourceTask.get());
            }
            if (!ProcessUtil.isNull(sourceTaskDto.getSourceTaskType())) {
                sourceTask.get().setSourceTaskType(sourceTaskType.get());
            }
            // Re-derived on every update that carries a payload, not only when tags are sent.
            // Updating the XML alone used to leave the tag rows describing the PREVIOUS payload,
            // so the editor then showed a configuration the task did not have.
            if (!ProcessUtil.isNull(sourceTaskDto.getXmlTagsInfo())
                || !ProcessUtil.isNull(sourceTaskDto.getTaskPayload())) {
                List<SourceTaskPayload> rows = this.tagRowsFor(sourceTaskDto, sourceTask.get().getTenantId());
                // Mutated in place rather than replaced: the collection is orphan-removal managed,
                // and handing Hibernate a new instance throws "A collection with cascade
                // all-delete-orphan was no longer referenced".
                if (sourceTask.get().getSourceTaskPayload() == null) {
                    sourceTask.get().setSourceTaskPayload(rows);
                } else {
                    sourceTask.get().getSourceTaskPayload().clear();
                    sourceTask.get().getSourceTaskPayload().addAll(rows);
                }
            }
            if (!ProcessUtil.isNull(sourceTaskDto.getTaskStatus())) {
                sourceTask.get().setTaskStatus(sourceTaskDto.getTaskStatus());
            }
            sourceTask.get().setHomePageId(references[0]);
            sourceTask.get().setPipelineId(sourceTaskDto.getPipelineId());
            sourceTask.get().setGroupId(references[1]);
            this.sourceTaskRepository.save(sourceTask.get());
            return new ResponseDto(SUCCESS, String.format("SourceTask successfully updated with ID %d.", sourceTaskDto.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskDto.getTaskDetailId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteSourceTask(SourceTaskDto sourceTaskDto) throws Exception {
        if (ProcessUtil.isNull(sourceTaskDto.getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceTask taskDetailId missing.");
        }

        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(sourceTaskDto.getTaskDetailId());
        if (sourceTask.isPresent() && !this.isOwnedByCaller(sourceTask.get())) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskDto.getTaskDetailId()));
        }
        if (sourceTask.isPresent()) {
            /*
             * A task in use cannot be deleted.
             *
             * Deleting one deletes every job bound to it -- the line below marks them all
             * Delete -- so removing a task someone thought was unused would take a few hundred
             * jobs with it and stop work nobody meant to stop. The jobs have to be moved to
             * another task or deleted deliberately first, which makes that an explicit choice
             * rather than a side effect.
             */
            long liveJobs = this.sourceJobRepository.countLiveJobsForTask(sourceTaskDto.getTaskDetailId());
            if (liveJobs > 0) {
                return new ResponseDto(ERROR, String.format(
                    "\"%s\" still has %d job%s using it. Point those jobs at another task, or "
                        + "delete them, before deleting this one.",
                    sourceTask.get().getTaskName(), liveJobs, liveJobs == 1 ? "" : "s"));
            }
            /*
             * Unconditional, because deleting is what this endpoint does. The mark used to be
             * gated on the request carrying a taskStatus -- a field the method does not require
             * and does not read the value of -- while the cascade to the jobs below ran either
             * way and the success message was written either way. A caller that sent only
             * taskDetailId, the one field the endpoint declares as required, was told
             * "SourceTask successfully deleted with ID N." and the task stayed Active in every
             * list and every picker. The console only avoids it by sending the field on purpose.
             */
            sourceTask.get().setTaskStatus(Status.Delete);
            this.sourceTaskRepository.save(sourceTask.get());
            this.sourceJobRepository.statusChangeSourceJobWithSourceTaskId(sourceTaskDto.getTaskDetailId(), Status.Delete.name());
            return new ResponseDto(SUCCESS, String.format("SourceTask successfully deleted with ID %d.", sourceTaskDto.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskDto.getTaskDetailId()));
    }

    @Override
    public ResponseDto listSourceTask(String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto;
        Object countQueryResult = this.queryService.executeQueryForSingleResult(this.queryService.listSourceTaskQuery(
        true, startDate, endDate, columnName, order, searchTextDto));
        if (!ProcessUtil.isNull(countQueryResult)) {

            List<Object[]> result = this.queryService.executeQuery(this.queryService.listSourceTaskQuery(
            false, startDate, endDate, columnName, order, searchTextDto), paging);
            if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
                List<SourceTaskDto> sourceTaskDtoList = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    SourceTaskDto sourceTaskDto = new SourceTaskDto();
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setTaskDetailId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setTaskName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setTaskPayload(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setHomePageId(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setPipelineId(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setGroupId(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setTaskStatus(EnumConverter.toStatus(String.valueOf(obj[index])));
                    }
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setSourceTaskTypeId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setServiceName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setDescription(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setQueueTopicPartition(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setStatus(EnumConverter.toStatus(obj[index].toString()));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskTypeDto.setKafkaConnectionProfileId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setBucket(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setInputFolder(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setOutputFolder(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceTaskDto.setTotalLinksJobs(Long.valueOf(obj[index].toString()));
                    }
                    sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
                    sourceTaskDtoList.add(sourceTaskDto);
                }
                this.userNameResolver.attachToDtos(sourceTaskDtoList, this.sourceTaskRepository,
                    SourceTask::getTaskDetailId);
                responseDto = new ResponseDto(SUCCESS, "SourceTask successfully ", sourceTaskDtoList,
                    PagingUtil.convertEntityToPagingDTO(Long.valueOf(countQueryResult.toString()), paging));
            } else {
                responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());
            }
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto fetchAllLinkJobsWithSourceTaskId(Long sourceTaskId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto = new ResponseDto(SUCCESS, "No Data found.", new ArrayList<>());;
        /*
         * Paged and counted the way listSourceTask is. The endpoint has always declared page,
         * limit, columnName and order, and this method took the Pageable built from them and
         * never referenced it: it called the single-argument executeQuery overload, so every row
         * came back whatever page was asked for, in no particular order, with no PagingDto for a
         * paginator to read. A caller asking for page 2 got page 1 again, and the shipped legacy
         * console asking for a 500-row cap was served the lot.
         *
         * The count went through executeQuery too, which returns a List -- never null and never
         * "" -- so the isNull guard below it could not fire and the query was a wasted round trip.
         * executeQueryForSingleResult gives the number the guard and the paging block both need.
         */
        Object countQueryResult = this.queryService.executeQueryForSingleResult(
            this.queryService.fetchAllLinkJobsWithSourceTaskQuery(true, sourceTaskId, startDate, endDate, searchTextDto));
        if (!ProcessUtil.isNull(countQueryResult)) {
            List<Object[]> result = this.queryService.executeQuery(
                this.queryService.fetchAllLinkJobsWithSourceTaskQuery(false, sourceTaskId, startDate, endDate,
                    columnName, order, searchTextDto), paging);
            if (!ProcessUtil.isNull(result) && !result.isEmpty()) {
                List<SourceJobDto> sourceJobDtoList = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    SourceJobDto sourceJobDto = new SourceJobDto();
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setJobId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setJobName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setJobStatus(EnumConverter.toStatus(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setExecution(EnumConverter.toExecution(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setJobRunningStatus(EnumConverter.toJobStatus(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setLastJobRun(LocalDateTime.parse(String.valueOf(obj[index]), formatter));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setPriority(Integer.valueOf(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!ProcessUtil.isNull(obj[index])) {
                        sourceJobDto.setDateCreated((Timestamp) obj[index]);
                    }
                    sourceJobDtoList.add(sourceJobDto);
                }
                responseDto = new ResponseDto(SUCCESS, "LinkJobsWithSourceTask successfully ", sourceJobDtoList,
                    PagingUtil.convertEntityToPagingDTO(Long.valueOf(countQueryResult.toString()), paging));
            }
        }
        return responseDto;
    }

    @Transactional(readOnly = true)
    /**
     * The tag rows for a task, taken from the caller's own tags or derived from its XML.
     *
     * A task carries its configuration twice: as the XML the worker runs, and as tag rows the
     * editor renders. Nothing kept the two in step, so a caller sending only the XML -- the API,
     * a bulk import, a hand-written payload -- created a task that ran correctly and opened in the
     * editor with every field blank. Saving from that screen then wrote the blanks back over the
     * XML that worked, which is the actual loss.
     *
     * The XML is the source of truth and the rows are derived from it whenever they were not
     * supplied, so the two cannot disagree by omission.
     */
    /**
     * The tag rows carry their task's tenant from the start (V102): Hibernate inserts them before it points them at the
     * task, so the database cannot take the tenant from the task for them.
     */
    private List<SourceTaskPayload> tagRowsFor(SourceTaskDto sourceTaskDto, Long tenantId) {
        List<ConfigurationMakerRequest.TagInfo> tags = sourceTaskDto.getXmlTagsInfo();
        if (ProcessUtil.isNull(tags) || tags.isEmpty()) {
            tags = XmlOutTagInfoUtil.parseXmlToTags(sourceTaskDto.getTaskPayload());
        }
        return tags.stream().map(tagInfo -> {
            SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
            sourceTaskPayload.setTagKey(tagInfo.getTagKey());
            sourceTaskPayload.setTagParent(tagInfo.getTagParent());
            sourceTaskPayload.setTagValue(tagInfo.getTagValue());
            sourceTaskPayload.setTenantId(tenantId);
            return sourceTaskPayload;
        }).collect(Collectors.toList());
    }

    public ResponseDto fetchSourceTaskWithSourceTaskId(Long sourceTaskId) {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        // With its tag rows (MIG-67): they are mapped below, outside any transaction.
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findWithPayloadByTaskDetailId(sourceTaskId);
        // The by-id read has to agree with the list the caller came from: a soft-deleted task
        // reads as absent, exactly like one belonging to another tenant.
        if (sourceTask.isPresent()
            && (!this.isOwnedByCaller(sourceTask.get()) || this.isDeleted(sourceTask.get()))) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskId));
        }
        if (sourceTask.isPresent()) {
            SourceTaskDto sourceTaskDto = new SourceTaskDto();
            sourceTaskDto.setTaskDetailId(sourceTask.get().getTaskDetailId());
            // Carried so the editor can ask formForPipeline for THIS task's tenant. Without it
            // a platform admin -- who sees every tenant's rows -- was served whichever tenant's
            // form for this pipeline had the lower id, and saving wrote that form's fields into
            // this task's tags and payload. The guard on both sides already existed; the one
            // line that feeds it did not, so it could never fire.
            sourceTaskDto.setTenantId(sourceTask.get().getTenantId());
            sourceTaskDto.setTaskName(sourceTask.get().getTaskName());
            sourceTaskDto.setTaskStatus(sourceTask.get().getTaskStatus());
            sourceTaskDto.setHomePageId(idText(sourceTask.get().getHomePageId()));
            sourceTaskDto.setPipelineId(sourceTask.get().getPipelineId());
            sourceTaskDto.setGroupId(idText(sourceTask.get().getGroupId()));
            sourceTaskDto.setTaskPayload(sourceTask.get().getTaskPayload());
            SourceTaskTypeDto sourceTaskTypeDto = this.getSourceTaskTypeDto(sourceTask.get().getSourceTaskType());
            sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
            if (!isNull(sourceTask.get().getSourceTaskPayload())) {
                sourceTaskDto.setXmlTagsInfo(
                    sourceTask.get().getSourceTaskPayload().stream().map(sourceTaskPayload -> {
                        ConfigurationMakerRequest.TagInfo xlm = new ConfigurationMakerRequest.TagInfo(
                            sourceTaskPayload.getTaskPayloadId(), sourceTaskPayload.getTagKey(),
                            sourceTaskPayload.getTagParent(), sourceTaskPayload.getTagValue());
                        return xlm;
                    }).collect(Collectors.toList()));
            }
            Collections.sort(sourceTaskDto.getXmlTagsInfo());
            return new ResponseDto(SUCCESS, String.format("SourceTask found with %d.", sourceTaskId), sourceTaskDto);
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskId));
    }

    @Override
    public ResponseDto fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId) throws Exception {
        // No type names no linked task. The parameter is optional at the door, and a null reached the query bound as
        // bytea -- a 500 for every caller (MIG-259, the api-check suite).
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "Source task type missing.");
        }
        Long tenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();

        List<SourceTaskProjection> sourceTasks = tenantId == null
            ? this.sourceTaskRepository.fetchAllLinkSourceTaskWithSourceTaskTypeId(sourceTaskTypeId)
            : this.sourceTaskRepository.fetchAllLinkSourceTaskWithSourceTaskTypeIdForTenant(sourceTaskTypeId, tenantId);
        return new ResponseDto(SUCCESS, String.format("SourceTask fetch with SourceTaskTypeId %d.", sourceTaskTypeId),
            sourceTasks);
    }

    @Override
    public ByteArrayOutputStream downloadListSourceTask() throws Exception {
        List<SourceTaskProjection> sourceTask = TenantContext.isPlatformAdmin()
            ? this.sourceTaskRepository.downloadListSourceTask()
            : this.sourceTaskRepository.downloadListSourceTaskForTenant(TenantContext.getTenantId());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(ListSourceTask);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), SOURCE_TASK_HEADER);
        sourceTask.forEach(sourceTaskProjection -> {
            rowCount.getAndIncrement();
            List<String> dataCellValue = new ArrayList<>();
            dataCellValue.add(!isNull(sourceTaskProjection.getTaskDetailId()) ? String.valueOf(sourceTaskProjection.getTaskDetailId()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getTaskName()) ? String.valueOf(sourceTaskProjection.getTaskName()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getTaskPayload()) ? String.valueOf(sourceTaskProjection.getTaskPayload()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getTaskStatus()) ? String.valueOf(sourceTaskProjection.getTaskStatus()) : "");

            dataCellValue.add(!isNull(sourceTaskProjection.getPipelineTaskId()) ? String.valueOf(sourceTaskProjection.getPipelineTaskId()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getHomePage()) ? String.valueOf(sourceTaskProjection.getHomePage()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getServiceName()) ? String.valueOf(sourceTaskProjection.getServiceName()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getQueueTopicPartition()) ? String.valueOf(sourceTaskProjection.getQueueTopicPartition()) : "");
            this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
        }
    }

    @Override
    public ByteArrayOutputStream downloadSourceTaskTemplate() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(ListSourceTask);
        this.bulkExcel.setSheet(xssfSheet);
        int rowCount = 0;
        this.bulkExcel.fillBulkHeader(rowCount, UPLOAD_SOURCE_TASK_HEADER);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
        }
    }

    @Override
    public ResponseDto uploadSourceTask(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploadSourceTask file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream())) {
        if (isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new ResponseDto(ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(ListSourceTask);
        if(isNull(sheet)) {
            return new ResponseDto(ERROR, "Sheet not found with (ListSourceTask)");
        } else if (sheet.getLastRowNum() < 1) {
            return new ResponseDto(ERROR,  "You can't upload empty file.");
        } else if(sheet.getLastRowNum() > 1001) {
            return new ResponseDto(ERROR,"File support 1000 rows at a time.");
        }
        List<SourceTaskValidation> sourceTaskValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Row currentRow : sheet) {

            if (currentRow.getRowNum() == 0) {
                if (currentRow.getPhysicalNumberOfCells() != 5) {
                    return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1) + " heading missing.");
                }

                for (int i = 0; i < this.UPLOAD_SOURCE_TASK_HEADER.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.UPLOAD_SOURCE_TASK_HEADER[i])) {
                        return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1)
                                + this.UPLOAD_SOURCE_TASK_HEADER[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {

                SourceTaskValidation sourceTaskValidation = new SourceTaskValidation();
                sourceTaskValidation.setRowCounter(currentRow.getRowNum() + 1);

                for (int i = 0; i < this.UPLOAD_SOURCE_TASK_HEADER.length; i++) {
                    if (i == 0) {
                        sourceTaskValidation.setSourceTaskTypeId(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 1) {
                        sourceTaskValidation.setTaskName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 2) {
                        sourceTaskValidation.setTaskPayload(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 3) {
                        sourceTaskValidation.setPipelineId(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 4) {
                        sourceTaskValidation.setHomePageId(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                if (!ProcessUtil.isNull(sourceTaskValidation.getSourceTaskTypeId())) {
                    Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(Long.valueOf(sourceTaskValidation.getSourceTaskTypeId()));
                    // Another tenant's type reads as absent here, exactly as it does on the
                    // single-task path -- the spreadsheet must not become an id oracle either.
                    if (!sourceTaskType.isPresent() || !this.isSourceTaskTypeVisibleToCaller(sourceTaskType.get())) {
                        sourceTaskValidation.setErrorMsg("SourceTaskType does not exist at row " + (currentRow.getRowNum() + 1) + ".\n");
                    } else if (sourceTaskType.get().getStatus().equals(Status.Delete)) {
                        sourceTaskValidation.setErrorMsg("Deleted sourceTaskType is not linked with source task at row " + (currentRow.getRowNum() + 1) + ".\n");
                    } else if (sourceTaskType.get().getStatus().equals(Status.Inactive)) {
                        sourceTaskValidation.setErrorMsg("Inactive sourceTaskType is not linked with source task at row " + (currentRow.getRowNum() + 1) + ".\n");
                    }
                }
                sourceTaskValidation.isValidSourceTask();
                if (!ProcessUtil.isNull(sourceTaskValidation.getErrorMsg())) {
                    errors.add(sourceTaskValidation.getErrorMsg());
                    continue;
                }
                sourceTaskValidations.add(sourceTaskValidation);
            }
        }
        if (!errors.isEmpty()) {
            return new ResponseDto(ERROR, String.format("Total %d source task invalid.", errors.size()), errors);
        }
        final Long[] uploadTenantIdHolder = new Long[1];
        ResponseDto tenantError = this.resolveTenantIdForCreate(object.getTenantId(), id -> uploadTenantIdHolder[0] = id);
        if (tenantError != null) {
            return tenantError;
        }
        Long uploadTenantId = uploadTenantIdHolder[0];
        Map<SourceTaskValidation, Long> homePageByRow = new HashMap<>();
        for (SourceTaskValidation row : sourceTaskValidations) {
            String referenceError = this.refuseReference(row.getHomePageId(), HOME_PAGES, "Home page", uploadTenantId,
                id -> homePageByRow.put(row, id));
            if (referenceError != null) {
                errors.add(String.format("%s at row %d.%n", referenceError.substring(0, referenceError.length() - 1), row.getRowCounter()));
            }
            // The payload-only rules ran in isValidSourceTask; what needs the workspace runs here, now it is known.
            Optional<String> configuration = this.refuseConfiguration(row.getTaskPayload(), uploadTenantId);
            if (configuration.isPresent()) {
                errors.add(String.format("%s (row %d)%n", configuration.get(), row.getRowCounter()));
            }
        }
        if (!errors.isEmpty()) {
            return new ResponseDto(ERROR, String.format("Total %d source task invalid.", errors.size()), errors);
        }
        sourceTaskValidations.forEach(sourceTaskValidation -> {
            SourceTask sourceTask = new SourceTask();

            sourceTask.setTenantId(uploadTenantId);
            sourceTask.setTaskName(sourceTaskValidation.getTaskName());
            sourceTask.setTaskPayload(sourceTaskValidation.getTaskPayload());
            sourceTask.setPipelineId(sourceTaskValidation.getPipelineId());
            sourceTask.setHomePageId(homePageByRow.get(sourceTaskValidation));
            sourceTask.setTaskStatus(Status.Active);
            sourceTask.setSourceTaskType(this.sourceTaskTypeRepository.findById(Long.valueOf(sourceTaskValidation.getSourceTaskTypeId())).get());
            this.applyDerivedLocation(sourceTask);
            sourceTask.setSourceTaskPayload(sourceTaskValidation.getXmlTagsInfo()
                .stream().map(tagInfo -> {
                    SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                    sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                    sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                    sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                    sourceTaskPayload.setTenantId(sourceTask.getTenantId());
                    return sourceTaskPayload;
                }).collect(Collectors.toList()));
            this.sourceTaskRepository.save(sourceTask);
        });
        this.notifications.notificationCreated(TenantContext.getTenantId(), Notices.notice(TenantContext.getAppUserId(), NotificationType.BATCH_DONE, NotificationSeverity.INFO, "Batch upload finished", String.format("Total %d tasks saved successfully.", sourceTaskValidations.size()), "/taskList"));
        return new ResponseDto(SUCCESS, String.format("Total %d task save successfully", sourceTaskValidations.size()));
        }
    }

    private SourceTaskTypeDto getSourceTaskTypeDto(SourceTaskType sourceTaskType) {
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
        sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
        sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
        sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
        sourceTaskTypeDto.setStatus(sourceTaskType.getStatus());
        sourceTaskTypeDto.setKafkaConnectionProfileId(sourceTaskType.getKafkaConnectionProfileId());
        return sourceTaskTypeDto;
    }
}
