package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTFLinkSTT;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTFLinkSTTRepository extends CrudRepository<STTFLinkSTT, Long> {

    @Query(value = "SELECT count(sttfLinkSTT.auSttfId) FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.stt.sttId = ?1 AND sttfLinkSTT.status != ?2")
    public Long countBySttIdAndNotInStatus(Long sttId, Long status);

    @Query(value = "SELECT count(sttfLinkSTT.auSttfId) FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.sttf.sttfId = ?1 AND sttfLinkSTT.status != ?2")
    public Long countBySttfIdAndNotInStatus(Long sttfId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT WHERE sttfLinkSTT.stt.sttId = ?1 " +
        "AND sttfLinkSTT.sttf.sttfId = ?2 AND sttfLinkSTT.appUser.appUserId = ?3 AND sttfLinkSTT.status = ?4")
    public Optional<STTFLinkSTT> findBySttIdAndSttfIdAndAppUserIdAndStatus(Long sttId, Long sttfId, Long appUserId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.auSttfId = ?1 AND sttfLinkSTT.stt.sttId = ?2 AND sttfLinkSTT.sttf.sttfId = ?3 " +
        " AND sttfLinkSTT.appUser.appUserId = ?4 AND sttfLinkSTT.status != ?5")
    public Optional<STTFLinkSTT> findByAuSttfIdAndSttIdAndSttfIdAndAppUserIdAndStatusNotIn(
            Long auSttId, Long sttId, Long sttfId, Long appUserId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.sttf.sttfId = ?1 AND sttfLinkSTT.status != ?2")
    public List<STTFLinkSTT> findBySttfIdAndStatusNotIn(Long sttfId, Long status);

    @Modifying
    @Query("UPDATE STTFLinkSTT sttfLinkSTT SET sttfLinkSTT.status = ?1 " +
        "WHERE sttfLinkSTT.stt.sttId = ?2 AND sttfLinkSTT.appUser.appUserId = ?3 AND sttfLinkSTT.status != ?1")
    public void deleteAllSTTFBySTTId(Long status, Long sttId, Long appUserId);

}
