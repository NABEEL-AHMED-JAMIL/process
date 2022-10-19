package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.dto.*;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.MessageQApiService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.SUCCESS;

/**
 * @author Nabeel Ahmed
 */
@Service
public class MessageQApiServiceImpl implements MessageQApiService {

    private Logger logger = LoggerFactory.getLogger(MessageQApiServiceImpl.class);

    @Autowired
    private QueryService queryService;
    @Autowired
    private SourceJobRepository jobRepository;
    @Autowired
    private JobQueueRepository jobQueueRepository;

    @Override
    public ResponseDto fetchLogs(MessageQSearchDto messageQSearch) {
        ResponseDto responseDto = null;
        if (isNull(messageQSearch.getFromDate())) {
            return new ResponseDto(ERROR, "FromDate missing.");
        } else if (isNull(messageQSearch.getToDate())) {
            return new ResponseDto(ERROR, "ToDate missing.");
        }
        Map<String, Object> objectMap = new HashMap<>();
        List<Object[]> result = this.queryService.executeQuery(this.queryService.fetchJobQLog(messageQSearch, false));
        if (!isNull(result) && result.size() > 0) {
            List<SourceJobQueueDto> sourceJobQueues = new ArrayList<>();
            for(Object[] obj : result) {
                int index = 0;
                SourceJobQueueDto sourceJobQueue = new SourceJobQueueDto();
                if (!isNull(obj[index])) {
                    sourceJobQueue.setJobQueueId(Long.valueOf(obj[index].toString()));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setDateCreated(Timestamp.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setEndTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setJobId(Long.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setJobStatus(JobStatus.valueOf(String.valueOf(obj[index])));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setJobStatusMessage(String.valueOf(obj[index]));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setSkipTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                index++;
                if (!isNull(obj[index])) {
                    sourceJobQueue.setStartTime(LocalDateTime.parse(String.valueOf(obj[index]).substring(0,19), formatter));
                }
                sourceJobQueues.add(sourceJobQueue);
            }
            objectMap.put("sourceJobQueues", sourceJobQueues);
            result = this.queryService.executeQuery(this.queryService.fetchJobQLog(messageQSearch, true));
            if (!isNull(result) && result.size() > 0) {
                List<JobStatusStatisticDto> jobStatusStatistic = new ArrayList<>();
                for(Object[] obj : result) {
                    int index = 0;
                    jobStatusStatistic.add(new JobStatusStatisticDto(String.valueOf(obj[index]), Integer.valueOf(obj[++index].toString())));
                }
                objectMap.put("jobStatusStatistic", jobStatusStatistic);
            }
            responseDto = new ResponseDto(SUCCESS, "MessageQ successfully ", objectMap);
        } else {
            responseDto = new ResponseDto(SUCCESS, "No Data found for sourceTask.", new ArrayList<>());
        }
        return responseDto;
    }

    @Override
    public ResponseDto failJobLogs(Long jobQId) {
        if (isNull(jobQId)) {
            return new ResponseDto(ERROR, "JobQId missing.");
        }
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(jobQId);
        if (jobQueue.isPresent()) {
            // sub job status delete
            if (!jobQueue.get().getJobStatus().equals(JobStatus.Queue)) {
                return new ResponseDto(ERROR, "Only 'In Queue' Job can be fail.", jobQId);
            }
            jobQueue.get().setJobStatus(JobStatus.Failed);
            this.jobQueueRepository.save(jobQueue.get());
            // main job status delete
            Optional<SourceJob> sourceJob = this.jobRepository.findById(jobQueue.get().getJobId());
            sourceJob.get().setJobRunningStatus(JobStatus.Failed);
            this.jobRepository.save(sourceJob.get());
            return new ResponseDto(SUCCESS, "JobQueue successfully Update.", jobQId);
        }
        return new ResponseDto(ERROR, "JobQueue not found");
    }

}
