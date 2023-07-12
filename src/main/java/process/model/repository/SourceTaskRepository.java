package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.SourceTask;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {
}
