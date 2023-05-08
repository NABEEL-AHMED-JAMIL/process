package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.STTForm;
import process.model.projection.STTFProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTFormRepository extends CrudRepository<STTForm, Long> {

    @Query(value = "select sttf.*\n" +
        "from stt_form sttf\n" +
        "inner join app_users au on au.app_user_id  = sttf.app_user_id  \n" +
        "where sttf.sttf_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<STTForm> findBySttFIdAndAppUserUsername(Long sttfId, String username);

    @Query(value = "select sttf.*\n" +
        "from stt_form sttf\n" +
        "inner join app_users au on au.app_user_id  = sttf.app_user_id  \n" +
        "where sttf.sttf_id = ?1 and au.username = ?2 and sttf.status != ?3", nativeQuery = true)
    public Optional<STTForm> findBySttFIdAndAppUserUsernameAndNotInStatus(Long sttfId, String username, Long status);

    @Query(value = "select sf.sttf_id  as sttFId, sf.sttf_name as sttFName,\n" +
        "sf.description as description, sf.status as status,\n" +
        "case when sf.is_default then true else false end as sttFDefault,\n" +
        "sf.date_created as dateCreated, '0' as totalUser,\n" +
        "'0' as totalTask, '0' as totalForm\n" +
        "from stt_form sf\n" +
        "inner join app_users au on au.app_user_id  = sf.app_user_id\n" +
        "where au.username = ?1 and sf.status != ?2\n" +
        "order by sf.sttf_id desc\n", nativeQuery = true)
    public List<STTFProjection> findByAppUserUsernameAndNotInStatus(String username, Long status);

}
