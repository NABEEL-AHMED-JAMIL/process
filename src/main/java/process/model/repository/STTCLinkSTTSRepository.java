package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTCLinkSTTS;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface STTCLinkSTTSRepository extends CrudRepository<STTCLinkSTTS, Long> {

    @Query(value = "SELECT count(sttcLinkSTTS.auSttcId) FROM STTCLinkSTTS sttcLinkSTTS " +
        "WHERE sttcLinkSTTS.stts.sttsId = ?1 AND sttcLinkSTTS.status != ?2")
    public Long countBySttsIdAndStatusNotIn(Long sttsId, Long status);

    @Query(value = "SELECT count(sttcLinkSTTS.auSttcId) FROM STTCLinkSTTS sttcLinkSTTS " +
        "WHERE sttcLinkSTTS.sttc.sttcId = ?1 AND sttcLinkSTTS.status != ?2")
    public Long countBySttcIdAndStatusNotIn(Long sttcId, Long status);

    @Query(value = "SELECT sttcLinkSTTS FROM STTCLinkSTTS sttcLinkSTTS " +
        "WHERE sttcLinkSTTS.auSttcId = ?1 AND sttcLinkSTTS.stts.sttsId = ?2 AND sttcLinkSTTS.appUser.appUserId = ?3 " +
        "AND sttcLinkSTTS.sttc.sttcId = ?4 AND sttcLinkSTTS.status != ?5")
    public Optional<STTCLinkSTTS> findByAuSttcIdAndSttsIdAndAppUserIdAndSttcIdAndStatusNotIn(
            Long auSttcId, Long sttsId, Long appUserId, Long sttcId, Long status);

    @Query(value = "SELECT sttcLinkSTTS FROM STTCLinkSTTS sttcLinkSTTS WHERE sttcLinkSTTS.sttc.sttcId = ?1 " +
        "AND sttcLinkSTTS.appUser.appUserId = ?2 AND sttcLinkSTTS.stts.sttsId = ?3 AND sttcLinkSTTS.status = ?4")
    public Optional<STTCLinkSTTS> findBySttcIdAndAppUserIdAndSttsIdAndStatus(Long sttcId, Long appUserId, Long sttsId, Long status);

    @Query(value = "SELECT sttcLinkSTTS FROM STTCLinkSTTS sttcLinkSTTS " +
        "WHERE sttcLinkSTTS.sttc.sttcId = ?1 AND sttcLinkSTTS.status != ?2")
    public List<STTCLinkSTTS> findBySttcIdAndStatusNotIn(Long sttcId, Long status);

    @Modifying
    @Query("UPDATE STTCLinkSTTS sttcLinkSTTS SET sttcLinkSTTS.status = ?1 " +
        "WHERE sttcLinkSTTS.stts.sttsId = ?2 AND sttcLinkSTTS.appUser.appUserId = ?3 AND sttcLinkSTTS.status != ?1")
    public void deleteAllSTTCySTTSId(Long status, Long sttsId, Long appUserId);

}


