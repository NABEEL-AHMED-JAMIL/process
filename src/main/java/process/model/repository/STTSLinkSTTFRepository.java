package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTFLinkSTT;
import process.model.pojo.STTSLinkSTTF;

import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTSLinkSTTFRepository extends CrudRepository<STTSLinkSTTF, Long> {

    @Query(value = "SELECT count(sttsLinkSTTF.auSttsId) FROM STTSLinkSTTF sttsLinkSTTF " +
            "WHERE sttsLinkSTTF.stts.sttsId = ?1 AND sttsLinkSTTF.status != ?2")
    public Long countBySttsIdAndNotInStatus(Long sttsId, Long status);

    @Query(value = "SELECT count(sttsLinkSTTF.auSttsId) FROM STTSLinkSTTF sttsLinkSTTF " +
            "WHERE sttsLinkSTTF.sttf.sttfId = ?1 AND sttsLinkSTTF.status != ?2")
    public Long countBySttfIdAndNotInStatus(Long sttfId, Long status);

    @Query(value = "SELECT sttsLinkSTTF FROM STTSLinkSTTF sttsLinkSTTF WHERE sttsLinkSTTF.stts.sttsId = ?1 " +
            "AND sttsLinkSTTF.appUser.appUserId = ?2 AND sttsLinkSTTF.sttf.sttfId = ?3 AND sttsLinkSTTF.status = ?4")
    public Optional<STTSLinkSTTF> findBySttsIdAndAppUserIdAndSttfIdAndStatus(Long sttsId, Long appUserId, Long sttfId, Long status);
}
