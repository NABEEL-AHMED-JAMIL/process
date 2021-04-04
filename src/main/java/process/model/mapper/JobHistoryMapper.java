package process.model.mapper;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import process.model.dto.JobHistoryDto;
import process.model.pojo.JobHistory;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("singleton")
public class JobHistoryMapper implements Mapper<JobHistory, JobHistoryDto> {

    /**
     * The method use to convert the job to jobHistory
     * @return JobHistoryDto
     */
    @Override
    public JobHistoryDto mapToDto(JobHistory jobHistory) {
        JobHistoryDto jobHistoryDto = new JobHistoryDto();
        if (jobHistory.getJobHistoryId() != null) {
            jobHistoryDto.setJobHistoryId(jobHistory.getJobHistoryId());
        }
        if (jobHistory.getStartTime() != null) {
            jobHistoryDto.setStartTime(jobHistory.getStartTime());
        }
        if (jobHistory.getEndTime() != null) {
            jobHistoryDto.setEndTime(jobHistory.getEndTime());
        }
        if (jobHistory.getJobId() != null) {
            jobHistoryDto.setJobId(jobHistory.getJobId());
        }
        if (jobHistory.getJobStatus() != null) {
            jobHistoryDto.setJobStatus(jobHistory.getJobStatus());
        }
        if (jobHistory.getJobStatusMessage() != null) {
            jobHistoryDto.setJobStatusMessage(jobHistory.getJobStatusMessage());
        }
        if (jobHistory.getDateCreated() != null) {
            jobHistoryDto.setDateCreated(jobHistory.getDateCreated());
        }
        return jobHistoryDto;
    }

    /**
     * The method use to convert the job to jobAuditLogs
     * @return JobHistory
     */
    @Override
    public JobHistory mapToEntity(JobHistoryDto jobHistoryDto) {
        JobHistory jobHistory = new JobHistory();
        if (jobHistoryDto.getJobHistoryId() != null) {
            jobHistory.setJobHistoryId(jobHistoryDto.getJobHistoryId());
        }
        if (jobHistoryDto.getStartTime() != null) {
            jobHistory.setStartTime(jobHistoryDto.getStartTime());
        }
        if (jobHistoryDto.getEndTime() != null) {
            jobHistory.setEndTime(jobHistoryDto.getEndTime());
        }
        if (jobHistoryDto.getJobId() != null) {
            jobHistory.setJobId(jobHistoryDto.getJobId());
        }
        if (jobHistoryDto.getJobStatus() != null) {
            jobHistory.setJobStatus(jobHistoryDto.getJobStatus());
        }
        if (jobHistoryDto.getJobStatusMessage() != null) {
            jobHistory.setJobStatusMessage(jobHistoryDto.getJobStatusMessage());
        }
        if (jobHistoryDto.getDateCreated() != null) {
            jobHistory.setDateCreated(jobHistoryDto.getDateCreated());
        }
        return jobHistory;
    }
}
