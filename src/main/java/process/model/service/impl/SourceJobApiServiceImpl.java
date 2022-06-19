package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.TaskDetailRepository;
import process.model.service.SourceJobApiService;
import process.util.ProcessTimeUtil;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceJobApiServiceImpl implements SourceJobApiService {

    private Logger logger = LoggerFactory.getLogger(SourceJobApiServiceImpl.class);

    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;
    @Autowired
    private TaskDetailRepository taskDetailRepository;

    @Override
    public ResponseDto addSourceJob(SourceJobDto tempSourceJob) throws Exception {
        if (isNull(tempSourceJob.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (isNull(tempSourceJob.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (isNull(tempSourceJob.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        } else if (isNull(tempSourceJob.getSchedulers()) || tempSourceJob.getSchedulers().size() == 0) {
            return new ResponseDto(ERROR, "SourceJob schedulers missing.");
        }
        tempSourceJob.getSchedulers()
                .stream().forEach(schedulerDto -> {

                });
        // validation for scheduler list -> if any missing then
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobName(tempSourceJob.getJobName());
        sourceJob.setTriggerDetail(this.taskDetailRepository.findById(
                tempSourceJob.getTaskDetail().getTaskDetailId()).get());
        sourceJob.setJobStatus(Status.Active);
        this.sourceJobRepository.saveAndFlush(sourceJob);
        tempSourceJob.getSchedulers().stream()
                .forEach(schedulerDto -> {
                    Scheduler scheduler = new Scheduler();
                    scheduler.setStartDate(schedulerDto.getStartDate());
                    if (!StringUtils.isEmpty(schedulerDto.getEndDate())) {
                        scheduler.setEndDate(schedulerDto.getEndDate());
                    }
                    scheduler.setStartTime(schedulerDto.getStartTime());
                    scheduler.setFrequency(schedulerDto.getFrequency());
                    if (!StringUtils.isEmpty(schedulerDto.getRecurrence())) {
                        scheduler.setRecurrence(schedulerDto.getRecurrence());
                    }
                    scheduler.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                            schedulerDto.getStartDate(), schedulerDto.getStartTime().toString()));
                    scheduler.setJobId(sourceJob.getJobId());
                    this.schedulerRepository.save(scheduler);
                });
        return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJob.getJobId()));
    }

    @Override
    public ResponseDto updateSourceJob(SourceJobDto tempSourceJob) throws Exception {
        return null;
    }

    @Override
    public ResponseDto listSourceJob(String startDate, String endDate, Pageable paging,
                                     SearchTextDto searchTextDto) throws Exception {
        return null;
    }
}
