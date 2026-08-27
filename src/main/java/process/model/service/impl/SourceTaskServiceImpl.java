package process.model.service.impl;

import org.apache.poi.ss.usermodel.Row;
import process.util.UserNameResolver;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.*;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.pojo.SourceTaskPayload;
import process.model.pojo.SourceTaskType;
import process.model.pojo.SourceTask;
import process.model.projection.SourceTaskProjection;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotificationCenterService;
import process.model.service.SourceTaskService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.PagingUtil;
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
    private final TenantRepository tenantRepository;
    private final NotificationCenterService notificationCenterService;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;


    public SourceTaskServiceImpl(BulkExcel bulkExcel,
        QueryService queryService,
        SourceJobRepository sourceJobRepository,
        SourceTaskRepository sourceTaskRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        TenantFilterHelper tenantFilterHelper,
        TaskPayloadLocationUtil taskPayloadLocationUtil,
        TenantRepository tenantRepository,
        NotificationCenterService notificationCenterService,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.bulkExcel = bulkExcel;
        this.queryService = queryService;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.taskPayloadLocationUtil = taskPayloadLocationUtil;
        this.tenantRepository = tenantRepository;
        this.notificationCenterService = notificationCenterService;
    }

    private ResponseDto resolveTenantIdForCreate(Long requestedTenantId, java.util.function.Consumer<Long> onResolved) {
        if (!TenantContext.isPlatformAdmin()) {
            onResolved.accept(TenantContext.getTenantId());
            return null;
        }
        if (ProcessUtil.isNull(requestedTenantId)) {
            return new ResponseDto(ERROR, "Tenant is required when creating a source task as Platform Admin.");
        }
        if (!this.tenantRepository.existsById(requestedTenantId)) {
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
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            sourceTaskDto.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        if (!sourceTaskType.isPresent()) {
            return new ResponseDto(ERROR, "Provided sourceTaskTypeId not found.");
        }
        SourceTask sourceTask = new SourceTask();
        ResponseDto tenantError = this.resolveTenantIdForCreate(sourceTaskDto.getTenantId(), sourceTask::setTenantId);
        if (tenantError != null) {
            return tenantError;
        }
        sourceTask.setTaskName(sourceTaskDto.getTaskName());
        sourceTask.setTaskPayload(sourceTaskDto.getTaskPayload());
        sourceTask.setHomePageId(sourceTaskDto.getHomePageId());
        sourceTask.setPipelineId(sourceTaskDto.getPipelineId());
        sourceTask.setGroupId(sourceTaskDto.getGroupId());
        sourceTask.setTaskStatus(Status.Active);
        sourceTask.setSourceTaskType(sourceTaskType.get());
        this.applyDerivedLocation(sourceTask);
        if (!ProcessUtil.isNull(sourceTaskDto.getXmlTagsInfo())) {
            sourceTask.setSourceTaskPayload(sourceTaskDto.getXmlTagsInfo()
                .stream().map(tagInfo -> {
                    SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                    sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                    sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                    sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                    return sourceTaskPayload;
                }).collect(Collectors.toList()));
        }
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
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            sourceTaskDto.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        if (!sourceTaskType.isPresent()) {
            return new ResponseDto(ERROR, "Active the linked sourceTaskType.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(sourceTaskDto.getTaskDetailId());
        if (sourceTask.isPresent() && !this.isOwnedByCaller(sourceTask.get())) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskDto.getTaskDetailId()));
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
            if (!ProcessUtil.isNull(sourceTaskDto.getXmlTagsInfo())) {
                sourceTask.get().setSourceTaskPayload(sourceTaskDto.getXmlTagsInfo()
                    .stream().map(tagInfo -> {
                        SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                        sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                        sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                        sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                        return sourceTaskPayload;
                    }).collect(Collectors.toList()));
            }
            if (!ProcessUtil.isNull(sourceTaskDto.getTaskStatus())) {
                sourceTask.get().setTaskStatus(sourceTaskDto.getTaskStatus());
            }
            sourceTask.get().setHomePageId(sourceTaskDto.getHomePageId());
            sourceTask.get().setPipelineId(sourceTaskDto.getPipelineId());
            sourceTask.get().setGroupId(sourceTaskDto.getGroupId());
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
            if (!ProcessUtil.isNull(sourceTaskDto.getTaskStatus())) {
                sourceTask.get().setTaskStatus(Status.Delete);
            }
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
        Object countQueryResult = this.queryService.executeQuery(
            this.queryService.fetchAllLinkJobsWithSourceTaskQuery(true, sourceTaskId, startDate, endDate, searchTextDto));
        if (!ProcessUtil.isNull(countQueryResult)) {
            List<Object[]> result = this.queryService.executeQuery(
                this.queryService.fetchAllLinkJobsWithSourceTaskQuery(false, sourceTaskId, startDate, endDate, searchTextDto));
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
                        sourceJobDto.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                    }
                    sourceJobDtoList.add(sourceJobDto);
                }
                responseDto = new ResponseDto(SUCCESS, "LinkJobsWithSourceTask successfully ", sourceJobDtoList);
            }
        }
        return responseDto;
    }

    @Transactional(readOnly = true)
    public ResponseDto fetchSourceTaskWithSourceTaskId(Long sourceTaskId) {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(sourceTaskId);
        if (sourceTask.isPresent() && !this.isOwnedByCaller(sourceTask.get())) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskId));
        }
        if (sourceTask.isPresent()) {
            SourceTaskDto sourceTaskDto = new SourceTaskDto();
            sourceTaskDto.setTaskDetailId(sourceTask.get().getTaskDetailId());
            sourceTaskDto.setTaskName(sourceTask.get().getTaskName());
            sourceTaskDto.setTaskStatus(sourceTask.get().getTaskStatus());
            sourceTaskDto.setHomePageId(sourceTask.get().getHomePageId());
            sourceTaskDto.setPipelineId(sourceTask.get().getPipelineId());
            sourceTaskDto.setGroupId(sourceTask.get().getGroupId());
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
                    if (!sourceTaskType.isPresent()) {
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
        sourceTaskValidations.forEach(sourceTaskValidation -> {
            SourceTask sourceTask = new SourceTask();

            sourceTask.setTenantId(uploadTenantId);
            sourceTask.setTaskName(sourceTaskValidation.getTaskName());
            sourceTask.setTaskPayload(sourceTaskValidation.getTaskPayload());
            sourceTask.setPipelineId(sourceTaskValidation.getPipelineId());
            sourceTask.setHomePageId(sourceTaskValidation.getHomePageId());
            sourceTask.setTaskStatus(Status.Active);
            sourceTask.setSourceTaskType(this.sourceTaskTypeRepository.findById(Long.valueOf(sourceTaskValidation.getSourceTaskTypeId())).get());
            this.applyDerivedLocation(sourceTask);
            sourceTask.setSourceTaskPayload(sourceTaskValidation.getXmlTagsInfo()
                .stream().map(tagInfo -> {
                    SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                    sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                    sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                    sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                    return sourceTaskPayload;
                }).collect(Collectors.toList()));
            this.sourceTaskRepository.save(sourceTask);
        });
        this.notificationCenterService.create(TenantContext.getTenantId(), TenantContext.getAppUserId(),
            NotificationType.BATCH_DONE, NotificationSeverity.INFO, "Batch upload finished",
            String.format("Total %d tasks saved successfully.", sourceTaskValidations.size()), "/taskList");
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
