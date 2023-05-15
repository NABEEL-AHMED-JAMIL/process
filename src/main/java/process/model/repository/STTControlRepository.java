package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTControl;
import process.model.projection.STTCProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTControlRepository extends CrudRepository<STTControl, Long> {

    @Query(value = "select sttc.*\n" +
        "from stt_control sttc\n" +
        "inner join app_users au on au.app_user_id  = sttc.app_user_id  \n" +
        "where sttc.sttc_id = ?1 and au.username = ?2 and sttc.status != ?3", nativeQuery = true)
    public Optional<STTControl> findBySttCIdAndAppUserUsernameAndNotInStatus(Long sttcId, String username, Long status);

    @Query(value = "select sc.sttc_id as sttcId, sc.sttc_order as sttcOrder, sc.sttc_name as sttcName,\n" +
        "sc.filed_name as filedName, sc.filed_type as filedType, sc.status as status, \n" +
        "case when sc.is_default then true else false  end as sttcDefault,\n" +
        "case when sc.mandatory then true else false  end as mandatory,\n" +
        "sc.date_created as dateCreated, '0' as totalStt, '0' as totalForm, '0' as totalSection\n" +
        "from stt_control sc\n" +
        "join app_users au on au.app_user_id  = sc.app_user_id \n" +
        "where au.username = ?1 and sc.status != ?2\n" +
        "order by sc.sttc_id desc\n", nativeQuery = true)
    public List<STTCProjection> findByAppUserUsernameAndNotInStatus(String username, Long status);

    @Query(value = "select sttc.*\n" +
        "from stt_control sttc\n" +
        "inner join app_users au on au.app_user_id  = sttc.app_user_id  \n" +
        "where au.username = ?1 and sttc.status != ?2", nativeQuery = true)
    public List<STTControl> findSttControlByAppUserUsernameAndNotInStatus(String username, Long status);
}
