package process.model.mapper;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import process.model.dto.JobDto;
import process.model.pojo.Job;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("singleton")
public class JobMapper implements Mapper<Job, JobDto> {

    /**
     * The method use to convert the job to jobDto
     * @return JobDto
     */
    @Override
    public JobDto mapToDto(Job job) {
        JobDto jobDto = new JobDto();
        if (job.getJobId() != null) {
            jobDto.setJobId(job.getJobId());
        }
        if (job.getJobName() != null) {
            jobDto.setJobName(job.getJobName());
        }
        if (job.getTriggerDetail() != null) {
            jobDto.setTriggerDetail(job.getTriggerDetail());
        }
        if (job.getJobStatus() != null) {
            jobDto.setJobStatus(job.getJobStatus());
        }
        if (job.getJobRunningStatus() != null) {
            jobDto.setJobRunningStatus(job.getJobRunningStatus());
        }
        if (job.getLastJobRun() != null) {
            jobDto.setLastJobRun(job.getLastJobRun());
        }
        return jobDto;
    }

    /**
     * The method use to convert the jobDto to job
     * @return Job
     */
    @Override
    public Job mapToEntity(JobDto jobDto) {
        Job job = new Job();
        if (jobDto.getJobId() != null) {
            job.setJobId(jobDto.getJobId());
        }
        if (jobDto.getJobName() != null) {
            job.setJobName(jobDto.getJobName());
        }
        if (jobDto.getTriggerDetail() != null) {
            job.setTriggerDetail(jobDto.getTriggerDetail());
        }
        if (jobDto.getJobStatus() != null) {
            job.setJobStatus(jobDto.getJobStatus());
        }
        if (jobDto.getJobRunningStatus() != null) {
            job.setJobRunningStatus(jobDto.getJobRunningStatus());
        }
        if (jobDto.getLastJobRun() != null) {
            job.setLastJobRun(jobDto.getLastJobRun());
        }
        return job;
    }
}
