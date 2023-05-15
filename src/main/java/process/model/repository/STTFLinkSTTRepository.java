package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTFLinkSTT;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTFLinkSTTRepository extends CrudRepository<STTFLinkSTT, Long> {

    @Query(value = "SELECT count(sttfLinkSTT.auSttfId) FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.stt.sttId = ?1 AND sttfLinkSTT.status != ?2")
    public Long countBySttIdAndNotInStatus(Long sttId, Long status);

    @Query(value = "SELECT count(sttfLinkSTT.auSttfId) FROM STTFLinkSTT sttfLinkSTT " +
            "WHERE sttfLinkSTT.sttf.sttfId = ?1 AND sttfLinkSTT.status != ?2")
    public Long countBySttfIdAndNotInStatus(Long sttfId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT WHERE sttfLinkSTT.stt.sttId = ?1 " +
        "AND sttfLinkSTT.appUser.appUserId = ?2 AND sttfLinkSTT.sttf.sttfId = ?3 AND sttfLinkSTT.status = ?4")
    public Optional<STTFLinkSTT> findBySttIdAndAppUserIdAndSttfIdAndStatus(Long sttId, Long appUserId, Long sttFId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.auSttfId = ?1 AND sttfLinkSTT.stt.sttId = ?2 AND sttfLinkSTT.appUser.appUserId = ?3 " +
        "AND sttfLinkSTT.sttf.sttfId = ?4 AND sttfLinkSTT.status != ?5")
    public Optional<STTFLinkSTT> findByAuSttfIdAndSttIdAndAppUserIdAndSttfIdAndStatusNotIn(
            Long auSttId, Long sttId, Long appUserId, Long sttfId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT " +
        "WHERE sttfLinkSTT.stt.sttId = ?1 AND sttfLinkSTT.appUser.appUserId = ?2 AND sttfLinkSTT.status != ?3")
    public List<STTFLinkSTT> findBySttIdAndAppUserIdAndStatusNotIn(Long sttId, Long appUserId, Long status);

    @Query(value = "SELECT sttfLinkSTT FROM STTFLinkSTT sttfLinkSTT " +
            "WHERE sttfLinkSTT.sttf.sttfId = ?1 AND sttfLinkSTT.status != ?2")
    public List<STTFLinkSTT> findBySttfIdAndStatusNotIn(Long sttfId, Long status);

}
