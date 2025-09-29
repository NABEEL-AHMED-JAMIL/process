package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.KafkaTaskType;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface KafkaTaskTypeRepository extends CrudRepository<KafkaTaskType, Long> {
}
