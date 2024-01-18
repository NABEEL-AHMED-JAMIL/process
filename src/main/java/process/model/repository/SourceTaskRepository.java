package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceTask;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {
}
