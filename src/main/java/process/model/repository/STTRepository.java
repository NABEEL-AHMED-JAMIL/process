package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.STT;
import process.model.projection.STTProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTRepository extends CrudRepository<STT, Long> {

    @Query(value = "select stt.*\n" +
        "from stt stt\n" +
        "inner join app_users au on au.app_user_id = stt.app_user_id \n" +
        "where stt.stt_id = ?1 and au.username = ?2", nativeQuery = true)
    public Optional<STT> findBySttIdAndAppUser(Long sourceTaskTypeId, String username);

    @Query(value = "select stt.*\n" +
        "from stt stt\n" +
        "inner join app_users au on au.app_user_id = stt.app_user_id \n" +
        "where stt.stt_id = ?1 and au.username = ?2 and stt.status != ?3", nativeQuery = true)
    public Optional<STT> findBySttIdAndAppUserAndSttStatusNotIn(Long sttId, String username, Long status);

    @Query(value = "select stt.stt_id as sttId, stt.service_name as serviceName,\n" +
        "stt.description as description, stt.task_type as taskType,\n" +
        "stt.status as status, case when stt.is_default \n" +
        "then true else false end as sttDefault,\n" +
        "stt.date_created as dateCreated, '0' as totalUser,\n" +
        "'0' as totalTask, '0' as totalForm\n" +
        "from stt stt\n" +
        "inner join app_users au on au.app_user_id  = stt.app_user_id\n" +
        "where au.username = ?1 order by stt.stt_id desc\n ", nativeQuery = true)
    public List<STTProjection> findByAppUser(String username);

    @Query(value = "select stt.stt_id as sttId, stt.service_name as serviceName, stt.description as description,\n" +
        "stt.task_type as taskType, stt.status as status, case when stt.is_default then true else false  end as sttDefault,\n" +
        "stt.date_created as dateCreated\n" +
        "from stt stt\n" +
        "inner join app_users au on au.app_user_id  = stt.app_user_id\n" +
        "where au.username = ?1 and stt.status != ?2\n" +
        "group by stt.stt_id\n" +
        "order by stt.stt_id desc\n", nativeQuery = true)
    public List<STTProjection> findByAppUserAndStatusNotIn(String username, Long status);

    @Query(value = "select stt.*\n" +
        "from stt stt\n" +
        "inner join app_users au on au.app_user_id = stt.app_user_id \n" +
        "where au.username = ?1 and stt.status != ?2", nativeQuery = true)
    public List<STT> findSttByAppUserAndStatusNotIn(String username, Long status);

}
