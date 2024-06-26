package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTForm;
import process.model.projection.STTFProjection;
import process.util.lookuputil.GLookup;

import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTFormRepository extends CrudRepository<STTForm, Long> {

    public Optional<STTForm> findBySttfId(Long sttfId);

    @Query(value = "select sttf.* from stt_form sttf\n" +
        "inner join app_users au on au.app_user_id  = sttf.app_user_id  \n" +
        "where sttf.sttf_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<STTForm> findBySttfIdAndAppUser(Long sttfId, String username);

    @Query(value = "select sttf.* from stt_form sttf\n" +
        "inner join app_users au on au.app_user_id  = sttf.app_user_id  \n" +
        "where sttf.sttf_id = ?1 and au.username = ?2 and sttf.status != ?3", nativeQuery = true)
    public Optional<STTForm> findBySttfIdAndAppUserAndStatusNotIn(Long sttfId, String username, Long status);

    @Query(value = "select sf.sttf_id  as sttfId, sf.sttf_name as sttfName,\n" +
        "sf.description as description, sf.status as status, sf.form_type as formType,\n" +
        "case when sf.is_default then true else false end as sttFDefault, sf.home_page as homePage, " +
        "sf.service_id as serviceId, sf.date_created as dateCreated\n" +
        "from stt_form sf\n" +
        "inner join app_users au on au.app_user_id  = sf.app_user_id\n" +
        "where au.username = ?1 and sf.status != ?2\n" +
        "order by sf.sttf_id desc\n", nativeQuery = true)
    public List<STTFProjection> findByAppUserAndStatusNotIn(String username, Long status);

}
