package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface DatabaseConnectionProfileRepository extends JpaRepository<DatabaseConnectionProfile, Long> {

    List<DatabaseConnectionProfile> findByStatusNotOrderByDatabaseConnectionProfileIdDesc(Status status);

}
