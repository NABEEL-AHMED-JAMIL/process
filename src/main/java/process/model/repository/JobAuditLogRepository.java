package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobAuditLogs;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobAuditLogRepository extends CrudRepository<JobAuditLogs, Long> {
}
