package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceTaskType;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, String> {
}
