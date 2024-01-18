package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTSLinkSTTF;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTSLinkSTTFRepository extends CrudRepository<STTSLinkSTTF, Long> {

    @Query(value = "SELECT count(sttsLinkSTTF.auSttsId) FROM STTSLinkSTTF sttsLinkSTTF " +
            "WHERE sttsLinkSTTF.stts.sttsId = ?1 AND sttsLinkSTTF.status != ?2")
    public Long countBySttsIdAndStatusNotIn(Long sttsId, Long status);

    @Query(value = "SELECT count(sttsLinkSTTF.auSttsId) FROM STTSLinkSTTF sttsLinkSTTF " +
            "WHERE sttsLinkSTTF.sttf.sttfId = ?1 AND sttsLinkSTTF.status != ?2")
    public Long countBySttfIdAndStatusNotIn(Long sttfId, Long status);

    @Query(value = "SELECT sttsLinkSTTF FROM STTSLinkSTTF sttsLinkSTTF " +
        "WHERE sttsLinkSTTF.auSttsId = ?1 AND sttsLinkSTTF.stts.sttsId = ?2 AND sttsLinkSTTF.sttf.sttfId = ?3 " +
        "AND sttsLinkSTTF.appUser.appUserId = ?4 AND sttsLinkSTTF.status != ?5")
    public Optional<STTSLinkSTTF> findByAuSttsIdAndSttsIdAndSttfIdAndAppUserIdAndStatusNotIn(
        Long auSttsId, Long sttsId, Long sttfId, Long appUserId, Long status);

    @Query(value = "SELECT sttsLinkSTTF FROM STTSLinkSTTF sttsLinkSTTF WHERE sttsLinkSTTF.stts.sttsId = ?1 " +
        "AND sttsLinkSTTF.sttf.sttfId = ?2 AND sttsLinkSTTF.appUser.appUserId = ?3  AND sttsLinkSTTF.status = ?4")
    public Optional<STTSLinkSTTF> findBySttsIdAndSttfIdAndAppUserIdAndStatus(Long sttsId, Long sttfId, Long appUserId, Long status);

    @Query(value = "SELECT sttsLinkSTTF FROM STTSLinkSTTF sttsLinkSTTF " +
        "WHERE sttsLinkSTTF.stts.sttsId = ?1 AND sttsLinkSTTF.status != ?2")
    public List<STTSLinkSTTF> findBySttsIdAndStatusNotIn(Long sttfId, Long status);

    @Query(value = "SELECT sttsLinkSTTF FROM STTSLinkSTTF sttsLinkSTTF " +
            "WHERE sttsLinkSTTF.sttf.sttfId = ?1 AND sttsLinkSTTF.status != ?2")
    public List<STTSLinkSTTF> findBySttfIdAndStatusNotIn(Long sttfId, Long status);

    @Modifying
    @Query("UPDATE STTSLinkSTTF sttsLinkSTTF SET sttsLinkSTTF.status = ?1 " +
        "WHERE sttsLinkSTTF.sttf.sttfId = ?2 AND sttsLinkSTTF.appUser.appUserId = ?3 AND sttsLinkSTTF.status != ?1")
    public void deleteAllSTTSBySTTFId(Long status, Long sttfId, Long appUserId);
}
