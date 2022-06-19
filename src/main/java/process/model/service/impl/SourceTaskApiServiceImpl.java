package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.Status;
import process.model.pojo.TaskDetail;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TaskDetailRepository;
import process.model.service.SourceTaskApiService;
import java.util.Optional;
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
    private QueryService queryService;
    @Autowired
    private TaskDetailRepository taskDetailRepository;
    @Autowired
    private SourceTaskTypeRepository sourceTaskTypeRepository;

    @Override
    public ResponseDto addSourceTask(TaskDetailDto tempTaskDetail) throws Exception {
        // validation
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
        Optional<TaskDetail> taskDetail = this.taskDetailRepository.findTaskDetailByIdAndTaskStatus(
            tempTaskDetail.getTaskDetailId(), Status.Active);
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
    public ResponseDto listSourceTask(String startDate, String endDate, Pageable paging,
        SearchTextDto searchTextDto) throws Exception {
        return null;
    }

    @Override
    public ResponseDto fetchAllLinkJobsWithSourceTask(Long taskDetailId) throws Exception {
        return null;
    }


}
