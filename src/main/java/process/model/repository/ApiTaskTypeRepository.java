package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.ApiTaskType;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface ApiTaskTypeRepository extends CrudRepository<ApiTaskType, Long> {
}
