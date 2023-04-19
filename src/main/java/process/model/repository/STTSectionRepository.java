package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.STTSection;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTSectionRepository extends CrudRepository<STTSection, Long> {

    @Query(value = "select stts.*\n" +
        "from stt_section stts\n" +
        "join app_users au on au.app_user_id  = stts.app_user_id  \n" +
        "where stts.stts_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<STTSection> findBySttSIdAndAppUserUsername(Long sttsId, String username);
}
