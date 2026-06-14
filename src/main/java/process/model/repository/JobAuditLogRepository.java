package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobAuditLogs;
import process.model.enums.Status;
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
    @Query(value = "select job_audit_log_id as jobAuditLogId, job_queue_id as jobQueueId, log_detail as logsDetail, date_created as dateCreated, status as status " +
        "from job_audit_logs where job_queue_id = ? order by date_created asc", nativeQuery = true)
    public List<JobAuditLogProjection> findAllByJobQueueIdV1(Long jobQueueId);

    /**
     * Note: removed explicit native deleteByJobId method — prefer repository/service-layer
     * delete operations to remove audit logs for a job when required.
     */

    /**
     * Update status for audit logs belonging to job queues of a job
     * @param jobId
     * @param statusName the enum name as string (e.g., "Delete", "Active")
     * @return rows updated
     */
    @Transactional
    @Modifying
    @Query(value = "update job_audit_logs set status = ?2 where job_queue_id in (select job_queue_id from job_queue where job_id = ?1)", nativeQuery = true)
    public int updateStatusByJobId(Long jobId, String statusName);

}