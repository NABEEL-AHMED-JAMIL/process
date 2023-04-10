package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.SourceTaskType;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface SourceTaskTypeRepository extends CrudRepository<SourceTaskType, Long> {

    @Query(value = "select stt.*\n" +
        "from source_task_type stt\n" +
        "join app_users au on au.app_user_id  = stt.app_user_id  \n" +
        "where stt.source_task_type_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<SourceTaskType> findBySourceTaskTypeIdAndAppUserUsername(Long sourceTaskTypeId, String username);

}
