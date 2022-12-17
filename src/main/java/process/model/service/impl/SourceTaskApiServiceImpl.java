package process.model.service.impl;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceTaskPayload;
import process.model.pojo.SourceTaskType;
import process.model.pojo.SourceTask;
import process.model.projection.SourceTaskProjection;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.SourceTaskApiService;
import process.util.PagingUtil;
import process.util.excel.BulkExcel;
import process.util.validation.SourceTaskValidation;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.ERROR;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceTaskApiServiceImpl implements SourceTaskApiService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskApiServiceImpl.class);

    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private QueryService queryService;
    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SourceTaskRepository sourceTaskRepository;
    @Autowired
    private SourceTaskTypeRepository sourceTaskTypeRepository;

    private final String ListSourceTask = "ListSourceTask";
    private final String SOURCE_TASK_HEADER[] = {
        "Task Id", "Task Name", "Task Payload",
        "Task Status", "ServiceName", "QueueTopicPartition"
    };
    private final String UPLOAD_SOURCE_TASK_HEADER[] = {
        "TaskTypeId", "Task Name", "Task Payload"
    };

    @Override
    public ResponseDto addSourceTask(SourceTaskDto tempSourceTask) throws Exception {
        if (isNull(tempSourceTask.getTaskName())) {
            return new ResponseDto(ERROR, "SourceTask taskName missing.");
        } else if (isNull(tempSourceTask.getTaskPayload())) {
            return new ResponseDto(ERROR, "SourceTask taskPayload missing.");
        } else if (isNull(tempSourceTask.getSourceTaskType())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskType missing.");
        } else if (isNull(tempSourceTask.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskTypeId missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            tempSourceTask.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        if (!sourceTaskType.isPresent()) {
            return new ResponseDto(ERROR, "Provided sourceTaskTypeId not found.");
        }
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskName(tempSourceTask.getTaskName());
        sourceTask.setTaskPayload(tempSourceTask.getTaskPayload());
        sourceTask.setTaskHomePage(tempSourceTask.getTaskHomePage());
        sourceTask.setPipelineId(tempSourceTask.getPipelineId());
        sourceTask.setTaskStatus(Status.Active);
        sourceTask.setSourceTaskType(sourceTaskType.get());
        if (!isNull(tempSourceTask.getXmlTagsInfo())) {
            List<SourceTaskPayload> sourceTaskPayloads = new ArrayList<>();
            sourceTaskPayloads = tempSourceTask.getXmlTagsInfo()
            .stream().map(tagInfo -> {
                SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                return sourceTaskPayload;
            }).collect(Collectors.toList());
            sourceTask.setSourceTaskPayload(sourceTaskPayloads);
        }
        this.sourceTaskRepository.save(sourceTask);
        return new ResponseDto(SUCCESS, String.format("SourceTask successfully save with %d.", sourceTask.getTaskDetailId()));
    }

    @Override
    public ResponseDto updateSourceTask(SourceTaskDto tempSourceTask) throws Exception {
        if (isNull(tempSourceTask.getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceTask taskDetailId missing.");
        } else if (isNull(tempSourceTask.getTaskName())) {
            return new ResponseDto(ERROR, "SourceTask taskName missing.");
        } else if (isNull(tempSourceTask.getTaskPayload())) {
            return new ResponseDto(ERROR, "SourceTask taskPayload missing.");
        } else if (isNull(tempSourceTask.getSourceTaskType())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskType missing.");
        } else if (isNull(tempSourceTask.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTask sourceTaskTypeId missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            tempSourceTask.getSourceTaskType().getSourceTaskTypeId(), Status.Active);
        if (!sourceTaskType.isPresent()) {
            return new ResponseDto(ERROR, "Active the linked sourceTaskType.");
        }
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(tempSourceTask.getTaskDetailId());
        if (sourceTask.isPresent()) {
            if (!isNull(tempSourceTask.getTaskName())) {
                sourceTask.get().setTaskName(tempSourceTask.getTaskName());
            }
            if (!isNull(tempSourceTask.getTaskPayload())) {
                sourceTask.get().setTaskPayload(tempSourceTask.getTaskPayload());
            }
            if (!isNull(tempSourceTask.getSourceTaskType())) {
                sourceTask.get().setSourceTaskType(sourceTaskType.get());
            }
            if (!isNull(tempSourceTask.getXmlTagsInfo())) {
                List<SourceTaskPayload> sourceTaskPayloads = new ArrayList<>();
                sourceTaskPayloads = tempSourceTask.getXmlTagsInfo()
                .stream().map(tagInfo -> {
                    SourceTaskPayload sourceTaskPayload = new SourceTaskPayload();
                    sourceTaskPayload.setTagKey(tagInfo.getTagKey());
                    sourceTaskPayload.setTagParent(tagInfo.getTagParent());
                    sourceTaskPayload.setTagValue(tagInfo.getTagValue());
                    return sourceTaskPayload;
                }).collect(Collectors.toList());
                sourceTask.get().setSourceTaskPayload(sourceTaskPayloads);
            }
            if (!isNull(tempSourceTask.getTaskStatus())) {
                sourceTask.get().setTaskStatus(tempSourceTask.getTaskStatus());
            }
            if (!isNull(tempSourceTask.getTaskHomePage())) {
                sourceTask.get().setTaskHomePage(tempSourceTask.getTaskHomePage());
            }
            if (!isNull(tempSourceTask.getPipelineId())) {
                sourceTask.get().setPipelineId(tempSourceTask.getPipelineId());
            }
            this.sourceTaskRepository.save(sourceTask.get());
            return new ResponseDto(SUCCESS, String.format("SourceTask successfully update with %d.", tempSourceTask.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", tempSourceTask.getTaskDetailId()));
    }

    @Override
    public ResponseDto deleteSourceTask(SourceTaskDto tempSourceTask) throws Exception {
        if (isNull(tempSourceTask.getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceTask taskDetailId missing.");
        }
        /**
         * Note :- if the source task delete then delete all the source job link with source task
         * Use case :- if the source task type delete then only 'inactive or delete perform'
         * */
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(tempSourceTask.getTaskDetailId());
        if (sourceTask.isPresent()) {
            if (!isNull(tempSourceTask.getTaskStatus())) {
                sourceTask.get().setTaskStatus(Status.Delete);
            }
            this.sourceTaskRepository.save(sourceTask.get());
            this.sourceJobRepository.statusChangeSourceJobWithSourceTaskId(tempSourceTask.getTaskDetailId(), Status.Delete.name());
            return new ResponseDto(SUCCESS, String.format("SourceTask successfully update with %d.", tempSourceTask.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", tempSourceTask.getTaskDetailId()));
    }

    @Override
    public ResponseDto listSourceTask(Long appUserId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto;
        Object countQueryResult = this.queryService.executeQueryForSingleResult(this.queryService.listSourceTaskQuery(
                true, appUserId, startDate, endDate, columnName, order, searchTextDto));
        if (!isNull(countQueryResult)) {
            /* fetch Record According to Pagination*/
            List<Object[]> result = this.queryService.executeQuery(this.queryService.listSourceTaskQuery(
                    false, appUserId, startDate, endDate, columnName, order, searchTextDto), paging);
            if (!isNull(result) && result.size() > 0) {
                List<SourceTaskDto> sourceTaskDtoList = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    SourceTaskDto sourceTaskDto = new SourceTaskDto();
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTaskDetailId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTaskName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTaskPayload(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTaskHomePage(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setPipelineId(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTaskStatus(Status.valueOf(String.valueOf(obj[index])));
                    }
                    SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setSourceTaskTypeId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setDescription(String.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setSchemaRegister(Boolean.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setQueueTopicPartition(String.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setSchemaPayload(String.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setServiceName(String.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setStatus(Status.valueOf(obj[index].toString()));
                    }
                    index++;
                    sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
                    if (!isNull(obj[index])) {
                        sourceTaskDto.setTotalLinksJobs(Long.valueOf(obj[index].toString()));
                    }
                    sourceTaskDtoList.add(sourceTaskDto);
                }
                responseDto = new ResponseDto(SUCCESS, "SourceTask successfully ", sourceTaskDtoList,
                    PagingUtil.convertEntityToPagingDTO(Long.valueOf(countQueryResult.toString()), paging));
            } else {
                responseDto = new ResponseDto(SUCCESS, "No Data found for sourceTask.", new ArrayList<>());
            }
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for sourceTask.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto fetchAllLinkJobsWithSourceTaskId(Long appUserId, Long sourceTaskId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto = null;
        Object countQueryResult = this.queryService.executeQuery(
            this.queryService.fetchAllLinkJobsWithSourceTaskQuery(true, sourceTaskId, startDate, endDate, searchTextDto));
        if (!isNull(countQueryResult)) {
            List<Object[]> result = this.queryService.executeQuery(
                this.queryService.fetchAllLinkJobsWithSourceTaskQuery(false, sourceTaskId, startDate, endDate, searchTextDto));
            if (!isNull(result) && result.size() > 0) {
                List<SourceJobDto> sourceJobDtoList = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    SourceJobDto sourceJobDto = new SourceJobDto();
                    if (!isNull(obj[index])) {
                        sourceJobDto.setJobId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setJobName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setJobStatus(Status.valueOf(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setExecution(Execution.valueOf(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setJobRunningStatus(JobStatus.valueOf(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setLastJobRun(LocalDateTime.parse(String.valueOf(obj[index]), formatter));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setPriority(Integer.valueOf(String.valueOf(obj[index])));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceJobDto.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                    }
                    sourceJobDtoList.add(sourceJobDto);
                }
                responseDto = new ResponseDto(SUCCESS, "LinkJobsWithSourceTask successfully ", sourceJobDtoList);
            } else {
                responseDto = new ResponseDto(SUCCESS, "No Data found for LinkJobsWithSourceTask.", new ArrayList<>());
            }
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for LinkJobsWithSourceTask.", new ArrayList<>());
        }
        return responseDto;
    }

    public ResponseDto fetchSourceTaskWithSourceTaskId(Long sourceTaskId) {
        Optional<SourceTask> sourceTask = this.sourceTaskRepository.findById(sourceTaskId);
        if (sourceTask.isPresent()) {
            return new ResponseDto(SUCCESS, String.format("SourceTask found with %d.", sourceTaskId), sourceTask);
        }
        return new ResponseDto(ERROR, String.format("SourceTask not found with %d.", sourceTaskId));
    }

    @Override
    public ResponseDto fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId) throws Exception {
        return new ResponseDto(SUCCESS, String.format("SourceTask fetch with SourceTaskTypeId %d.", sourceTaskTypeId),
            this.sourceTaskRepository.fetchAllLinkSourceTaskWithSourceTaskTypeId(sourceTaskTypeId));
    }

    @Override
    public ByteArrayOutputStream downloadListSourceTask() throws Exception {
        List<SourceTaskProjection> sourceTask = this.sourceTaskRepository.downloadListSourceTask();
        XSSFWorkbook workbook = new XSSFWorkbook();
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
            dataCellValue.add(!isNull(sourceTaskProjection.getServiceName()) ? String.valueOf(sourceTaskProjection.getServiceName()) : "");
            dataCellValue.add(!isNull(sourceTaskProjection.getQueueTopicPartition()) ? String.valueOf(sourceTaskProjection.getQueueTopicPartition()) : "");
            this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    @Override
    public ByteArrayOutputStream downloadSourceTaskTemplate() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(ListSourceTask);
        this.bulkExcel.setSheet(xssfSheet);
        int rowCount = 0;
        this.bulkExcel.fillBulkHeader(rowCount, UPLOAD_SOURCE_TASK_HEADER);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    @Override
    public ResponseDto uploadSourceTask(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploadSourceTask file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        }
        // fill the stream with file into work-book
        XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream());
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
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            // header validation check
            if (currentRow.getRowNum() == 0) {
                if (currentRow.getPhysicalNumberOfCells() != 3) {
                    return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1) + " heading missing.");
                }
                // loop on the header
                for (int i=0; i < this.UPLOAD_SOURCE_TASK_HEADER.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.UPLOAD_SOURCE_TASK_HEADER[i])) {
                        return new ResponseDto(ERROR,"File at row " + (currentRow.getRowNum() + 1)
                            + this.UPLOAD_SOURCE_TASK_HEADER[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                // data validation and save
                SourceTaskValidation sourceTaskValidation = new SourceTaskValidation();
                sourceTaskValidation.setRowCounter(currentRow.getRowNum()+1);
                // get the row data and add into job-dto
                for (int i=0; i < this.UPLOAD_SOURCE_TASK_HEADER.length; i++) {
                    if (i==0) {
                        sourceTaskValidation.setSourceTaskTypeId(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==1) {
                        sourceTaskValidation.setTaskName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==2) {
                        sourceTaskValidation.setTaskPayload(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                if (!isNull(sourceTaskValidation.getSourceTaskTypeId())) {
                    Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(Long.valueOf(sourceTaskValidation.getSourceTaskTypeId()));
                    if (!sourceTaskType.isPresent()) {
                        sourceTaskValidation.setErrorMsg("SourceTaskType not exist at row " + (currentRow.getRowNum() + 1) + ".\n");
                    } else if (sourceTaskType.get().getStatus().equals(Status.Delete)) {
                        sourceTaskValidation.setErrorMsg("Delete sourceTaskType not link with source task at row " + (currentRow.getRowNum() + 1) + ".\n");
                    } else if (sourceTaskType.get().getStatus().equals(Status.Inactive)) {
                        sourceTaskValidation.setErrorMsg("Inactive sourceTaskType not link with source task at row " + (currentRow.getRowNum() + 1) + ".\n");
                    }
                }
                sourceTaskValidation.isValidSourceTask();
                if (!isNull(sourceTaskValidation.getErrorMsg())) {
                    errors.add(sourceTaskValidation.getErrorMsg());
                    continue;
                }
                sourceTaskValidations.add(sourceTaskValidation);
            }
        }
        if (errors.size() > 0) {
            return new ResponseDto(ERROR, String.format("Total %d source task invalid.", errors.size()), errors);
        }
        sourceTaskValidations.forEach(sourceTaskValidation -> {
            SourceTask sourceTask = new SourceTask();
            sourceTask.setTaskName(sourceTaskValidation.getTaskName());
            sourceTask.setTaskPayload(sourceTaskValidation.getTaskPayload());
            sourceTask.setTaskStatus(Status.Active);
            sourceTask.setSourceTaskType(this.sourceTaskTypeRepository.findById(
                    Long.valueOf(sourceTaskValidation.getSourceTaskTypeId())).get());
            this.sourceTaskRepository.save(sourceTask);
        });
        return new ResponseDto(SUCCESS, String.format("Total %d Task Save Successfully", sourceTaskValidations.size()));
    }
}
