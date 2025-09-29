package process.model.repository;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.NotificationAudit;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface NotificationAuditRepository extends CrudRepository<NotificationAudit, Long>,
    JpaSpecificationExecutor<NotificationAudit> {
}