package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceTaskData;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskDataRepository extends CrudRepository<SourceTaskData, Long> {
}
