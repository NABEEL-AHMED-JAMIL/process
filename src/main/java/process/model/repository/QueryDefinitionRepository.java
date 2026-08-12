package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.QueryDefinition;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface QueryDefinitionRepository extends JpaRepository<QueryDefinition, Long> {

    List<QueryDefinition> findByStatusNotOrderByQueryIdDesc(Status status);

    /** Used before deleting/deactivating a connection profile -- a profile with queries still
     * pointing at it must not be removable out from under them. */
    long countByDatabaseConnectionProfileIdAndStatusNot(Long databaseConnectionProfileId, Status status);

}
