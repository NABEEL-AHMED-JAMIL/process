package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobAuditLogs;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobAuditLogRepository extends JpaRepository<JobAuditLogs, Long> {

    List<JobAuditLogs> findAllByJobQueueId(Long jobQueueId);
}