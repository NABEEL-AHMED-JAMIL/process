package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.TaskDetail;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TaskDetailRepository;
import process.model.service.SourceTaskApiService;
import process.util.PagingUtil;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.ERROR;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceTaskApiServiceImpl implements SourceTaskApiService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskApiServiceImpl.class);

    @Autowired
    private QueryService queryService;
    @Autowired
    private TaskDetailRepository taskDetailRepository;
    @Autowired
    private SourceTaskTypeRepository sourceTaskTypeRepository;

    @Override
    public ResponseDto addSourceTask(TaskDetailDto tempTaskDetail) throws Exception {
        if (isNull(tempTaskDetail.getTaskName())) {
            return new ResponseDto(ERROR, "TaskDetail taskName missing.");
        } else if (isNull(tempTaskDetail.getTaskPayload())) {
            return new ResponseDto(ERROR, "TaskDetail taskPayload missing.");
        } else if (isNull(tempTaskDetail.getSourceTaskType())) {
            return new ResponseDto(ERROR, "TaskDetail sourceTaskType missing.");
        } else if (isNull(tempTaskDetail.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "TaskDetail sourceTaskTypeId missing.");
        }
        TaskDetail taskDetail = new TaskDetail();
        taskDetail.setTaskName(tempTaskDetail.getTaskName());
        taskDetail.setTaskPayload(tempTaskDetail.getTaskPayload());
        taskDetail.setTaskStatus(Status.Active);
        taskDetail.setSourceTaskType(this.sourceTaskTypeRepository
            .getOne(tempTaskDetail.getSourceTaskType().getSourceTaskTypeId()));
        this.taskDetailRepository.save(taskDetail);
        return new ResponseDto(SUCCESS, String.format(
            "TaskDetail successfully save with %d.", taskDetail.getTaskDetailId()));
    }

    @Override
    public ResponseDto updateSourceTask(TaskDetailDto tempTaskDetail) throws Exception {
        if (isNull(tempTaskDetail.getTaskDetailId())) {
            return new ResponseDto(ERROR, "TaskDetail taskDetailId missing.");
        } else if (isNull(tempTaskDetail.getTaskName())) {
            return new ResponseDto(ERROR, "TaskDetail taskName missing.");
        } else if (isNull(tempTaskDetail.getTaskPayload())) {
            return new ResponseDto(ERROR, "TaskDetail taskPayload missing.");
        } else if (isNull(tempTaskDetail.getSourceTaskType())) {
            return new ResponseDto(ERROR, "TaskDetail sourceTaskType missing.");
        } else if (isNull(tempTaskDetail.getSourceTaskType().getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "TaskDetail sourceTaskTypeId missing.");
        }
        Optional<TaskDetail> taskDetail = this.taskDetailRepository.findById(tempTaskDetail.getTaskDetailId());
        if (taskDetail.isPresent()) {
            if (!isNull(tempTaskDetail.getTaskName())) {
                taskDetail.get().setTaskName(tempTaskDetail.getTaskName());
            }
            if (!isNull(tempTaskDetail.getTaskPayload())) {
                taskDetail.get().setTaskPayload(tempTaskDetail.getTaskPayload());
            }
            if (!isNull(tempTaskDetail.getSourceTaskType())) {
                taskDetail.get().setSourceTaskType(this.sourceTaskTypeRepository.getOne(
                    tempTaskDetail.getSourceTaskType().getSourceTaskTypeId()));
            }
            if (!isNull(tempTaskDetail.getTaskStatus())) {
                taskDetail.get().setTaskStatus(tempTaskDetail.getTaskStatus());
            }
            this.taskDetailRepository.save(taskDetail.get());
            return new ResponseDto(SUCCESS, String.format("TaskDetail successfully update with %d.",
                tempTaskDetail.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("TaskDetail not found with %d.",
            tempTaskDetail.getTaskDetailId()));
    }

    @Override
    public ResponseDto deleteSourceTask(TaskDetailDto tempTaskDetail) throws Exception {
        if (isNull(tempTaskDetail.getTaskDetailId())) {
            return new ResponseDto(ERROR, "TaskDetail taskDetailId missing.");
        }
        /**
         * if user delete the task detail then all link sour-job should be stopped
         * */
        Optional<TaskDetail> taskDetail = this.taskDetailRepository
            .findByTaskDetailIdAndTaskStatus(tempTaskDetail.getTaskDetailId(), Status.Active);
        if (taskDetail.isPresent()) {
            if (!isNull(tempTaskDetail.getTaskStatus())) {
                taskDetail.get().setTaskStatus(Status.Delete);
            }
            this.taskDetailRepository.save(taskDetail.get());
            return new ResponseDto(SUCCESS, String.format("TaskDetail successfully update with %d.",
                tempTaskDetail.getTaskDetailId()));
        }
        return new ResponseDto(ERROR, String.format("TaskDetail not found with %d.",
            tempTaskDetail.getTaskDetailId()));
    }

    @Override
    public ResponseDto listSourceTask(Long appUserId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto = null;
        Object countQueryResult = this.queryService.executeQueryForSingleResult(
            this.queryService.listSourceTaskQuery(true, appUserId, startDate, endDate,
                columnName, order, searchTextDto));
        if (!isNull(countQueryResult)) {
            /* fetch Record According to Pagination*/
            List<Object[]> result = this.queryService.executeQuery(
                this.queryService.listSourceTaskQuery(false, appUserId, startDate, endDate,
                    columnName, order, searchTextDto), paging);
            if (!isNull(result) && result.size() > 0) {
                List<TaskDetailDto> taskDetailDtoList = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    TaskDetailDto taskDetailDto = new TaskDetailDto();
                    if (!isNull(obj[index])) {
                        taskDetailDto.setTaskDetailId(Long.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        taskDetailDto.setTaskName(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        taskDetailDto.setTaskPayload(String.valueOf(obj[index]));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        taskDetailDto.setTaskStatus(Status.valueOf(String.valueOf(obj[index])));
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
                        sourceTaskTypeDto.setQueueTopicPartition(String.valueOf(obj[index].toString()));
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
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setSchemaRegister(Boolean.valueOf(obj[index].toString()));
                    }
                    index++;
                    if (!isNull(obj[index])) {
                        sourceTaskTypeDto.setSchemaPayload(String.valueOf(obj[index].toString()));
                    }
                    index++;
                    taskDetailDto.setSourceTaskType(sourceTaskTypeDto);
                    if (!isNull(obj[index])) {
                        taskDetailDto.setTotalLinksJobs(Long.valueOf(obj[index].toString()));
                    }
                    taskDetailDtoList.add(taskDetailDto);
                }
                responseDto = new ResponseDto(SUCCESS, "TaskDetail successfully ", taskDetailDtoList,
                    PagingUtil.convertEntityToPagingDTO(Long.valueOf(countQueryResult.toString()), paging));
            } else {
                responseDto = new ResponseDto(SUCCESS, "No Data found for taskDetail.", new ArrayList<>());
            }
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for taskDetail.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto fetchAllLinkJobsWithSourceTask(Long appUserId, Long taskDetailId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        ResponseDto responseDto = null;
        Object countQueryResult = this.queryService.executeQuery(this.queryService.fetchAllLinkJobsWithSourceTaskQuery(true,
            taskDetailId, startDate, endDate, searchTextDto));
        if (!isNull(countQueryResult)) {
            List<Object[]> result = this.queryService.executeQuery(this.queryService.fetchAllLinkJobsWithSourceTaskQuery(false,
                taskDetailId, startDate, endDate, searchTextDto));
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
                        sourceJobDto.setLastJobRun(LocalDateTime.parse(String.valueOf(obj[index])));
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

    public ResponseDto fetchSourceTaskDetailWithSourceTaskId(Long taskDetailId) {
        Optional<TaskDetail> taskDetail = this.taskDetailRepository.findById(taskDetailId);
        if (taskDetail.isPresent()) {
            return new ResponseDto(SUCCESS, String.format("TaskDetail found with %d.", taskDetailId), taskDetail);
        }
        return new ResponseDto(ERROR, String.format("TaskDetail not found with %d.", taskDetailId));
    }

    @Override
    public ResponseDto downloadListSourceTask(Long appUserId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception {
        return null;
    }
}
