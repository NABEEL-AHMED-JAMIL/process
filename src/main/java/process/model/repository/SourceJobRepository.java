package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceJob;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceJobRepository extends CrudRepository<SourceJob, Long> {
}
