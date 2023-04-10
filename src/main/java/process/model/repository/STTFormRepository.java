package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.STTForm;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTFormRepository extends CrudRepository<STTForm, Long> {

    @Query(value = "select sttf.*\n" +
        "from stt_form sttf\n" +
        "join app_users au on au.app_user_id  = sttf.app_user_id  \n" +
        "where sttf.sttf_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<STTForm> findBySttFIdAndAppUserUsername(Long sttFId, String username);

}
