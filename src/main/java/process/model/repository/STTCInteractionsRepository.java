package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.STTCInteractions;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface STTCInteractionsRepository extends CrudRepository<STTCInteractions, Long> {

    Optional<STTCInteractions> findByInteractionsIdAndStatusNot(Long interactionsId, Long status);

    @Query(value = "SELECT sttcInteractions FROM STTCInteractions sttcInteractions WHERE sttcInteractions.sttsLinkSTTF.auSttsId = ?1 " +
        "AND sttcInteractions.sttc.sttcId = ?2 AND sttcInteractions.status != ?3 ")
    public Optional<STTCInteractions> findByAuSttsIdAndSttcIdAndStatusNotIn(Long auSttsId, Long sttcId, Long status);
}
