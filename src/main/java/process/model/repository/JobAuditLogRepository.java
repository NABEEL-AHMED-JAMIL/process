package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobAuditLogs;
import process.model.projection.JobAuditLogProjection;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobAuditLogRepository extends JpaRepository<JobAuditLogs, Long> {

    /**
     * Note :- Method use to find the job in queue by id
     * @param jobQueueId
     * @return List<JobAuditLogProjection>
     * */
    @Query(value = "select job_audit_log_id as jobAuditLogId, job_queue_id as jobQueueId, log_detail as logsDetail, date_created as dateCreated " +
        "from job_audit_logs where job_queue_id = ?", nativeQuery = true)
    public List<JobAuditLogProjection> findAllByJobQueueIdV1(Long jobQueueId);
}