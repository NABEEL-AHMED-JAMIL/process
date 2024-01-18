package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTSection;
import process.model.projection.STTSProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTSectionRepository extends CrudRepository<STTSection, Long> {

    @Query(value = "select stts.*\n" +
        "from stt_section stts\n" +
        "inner join app_users au on au.app_user_id = stts.app_user_id\n" +
        "where stts.stts_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<STTSection> findBySttsIdAndAppUser(Long sttsId, String username);

    @Query(value = "select stts.*\n" +
        "from stt_section stts\n" +
        "inner join app_users au on au.app_user_id = stts.app_user_id\n" +
        "where stts.stts_id = ?1 and au.username = ?2 and stts.status != ?3", nativeQuery = true)
    public Optional<STTSection> findBySttsIdAndAppUserAndStatusNotIn(Long sttsId, String username, Long status);

    @Query(value = "select stts.stts_id as sttsId, stts.stts_name as sttsName, stts.description as description,\n" +
        "stts.status as status, stts.is_default as sttsDefault,\n" +
        "stts.date_created as dateCreated, '0' as totalStt, '0' as totalForm, '0' as totalControl\n" +
        "from stt_section stts\n" +
        "inner join app_users au on au.app_user_id  = stts.app_user_id \n" +
        "where au.username = ?1 and stts.status != ?2 order by stts.stts_id desc", nativeQuery = true)
    public List<STTSProjection> findByAppUserAndStatusNotIn(String username, Long status);
}
