package process.model.mapper;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import process.model.dto.JobAuditLogsDto;
import process.model.pojo.JobAuditLogs;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("singleton")
public class JobAuditLogsMapper implements Mapper<JobAuditLogs, JobAuditLogsDto> {

    @Override
    public JobAuditLogsDto mapToDto(JobAuditLogs jobAuditLogs) {
        JobAuditLogsDto jobAuditLogsDto = new JobAuditLogsDto();
        if (jobAuditLogs.getJobAuditLogId() != null) {
            jobAuditLogsDto.setJobAuditLogId(jobAuditLogs.getJobAuditLogId());
        }
        if (jobAuditLogs.getJobId() != null) {
            jobAuditLogsDto.setJobId(jobAuditLogs.getJobId());
        }
        if (jobAuditLogs.getJobHistoryId() != null) {
            jobAuditLogsDto.setJobHistoryId(jobAuditLogs.getJobHistoryId());
        }
        if (jobAuditLogs.getLogsDetail() != null) {
            jobAuditLogsDto.setLogsDetail(jobAuditLogs.getLogsDetail());
        }
        if (jobAuditLogs.getDateCreated() != null) {
            jobAuditLogsDto.setDateCreated(jobAuditLogs.getDateCreated());
        }
        return jobAuditLogsDto;
    }

    @Override
    public JobAuditLogs mapToEntity(JobAuditLogsDto jobAuditLogsDto) {
        JobAuditLogs jobAuditLogs = new JobAuditLogs();
        if (jobAuditLogsDto.getJobAuditLogId() != null) {
            jobAuditLogs.setJobAuditLogId(jobAuditLogsDto.getJobAuditLogId());
        }
        if (jobAuditLogsDto.getJobId() != null) {
            jobAuditLogs.setJobId(jobAuditLogsDto.getJobId());
        }
        if (jobAuditLogsDto.getJobHistoryId() != null) {
            jobAuditLogs.setJobHistoryId(jobAuditLogsDto.getJobHistoryId());
        }
        if (jobAuditLogsDto.getLogsDetail() != null) {
            jobAuditLogs.setLogsDetail(jobAuditLogsDto.getLogsDetail());
        }
        if (jobAuditLogsDto.getDateCreated() != null) {
            jobAuditLogs.setDateCreated(jobAuditLogsDto.getDateCreated());
        }
        return jobAuditLogs;
    }
}
