package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import process.model.pojo.Scheduler;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {
}
