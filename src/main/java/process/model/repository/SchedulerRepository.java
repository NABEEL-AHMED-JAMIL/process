package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SchedulerRepository extends CrudRepository<Scheduler, Long> {
}
