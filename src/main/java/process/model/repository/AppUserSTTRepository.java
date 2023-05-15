package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.AppUserSTT;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface AppUserSTTRepository extends CrudRepository<AppUserSTT, Long> {

    @Query(value = "SELECT count(appUserSTT) FROM AppUserSTT appUserSTT " +
        "WHERE appUserSTT.stt.sttId = ?1 AND appUserSTT.status != ?2")
    public Long countBySttIdAndNotInStatus(Long sttId, Long status);

    @Query(value = "SELECT appUserSTT FROM AppUserSTT appUserSTT " +
        "WHERE appUserSTT.stt.sttId = ?1 AND appUserSTT.appUser.appUserId = ?2 AND appUserSTT.status = ?3")
    public Optional<AppUserSTT> findBySttIdAndAppUserIdAndStatus(Long sttId, Long appUserId, Long status);

    @Query(value = "SELECT appUserSTT FROM AppUserSTT appUserSTT WHERE appUserSTT.auSttId = ?1 " +
        "AND appUserSTT.appUser.appUserId = ?2 AND appUserSTT.createdBy.appUserId = ?3 AND appUserSTT.status != ?4")
    public Optional<AppUserSTT> findByAuSttIdAndAppUserIdAndCreatedByAndStatusNotIn(
        Long auSttId, Long appUserId, Long createdBy, Long status);

    @Query(value = "SELECT appUserSTT FROM AppUserSTT appUserSTT " +
        "WHERE appUserSTT.stt.sttId = ?1 AND appUserSTT.createdBy.appUserId = ?2 AND appUserSTT.status != ?3")
    public List<AppUserSTT> findBySttIdAndCreatedByAndStatusNotIn(Long sttId, Long createdBy, Long status);

}
