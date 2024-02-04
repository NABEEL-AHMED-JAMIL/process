package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.AppUserEnv;

import javax.transaction.Transactional;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface AppUserEnvRepository extends CrudRepository<AppUserEnv, Long> {

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "CALL public.insert_app_user_env(:appUserId,:createdBy)",nativeQuery = true)
    public int insertAppUserEnv(@Param("appUserId") Integer appUserId, @Param("createdBy") Integer createdBy);


    @Query(value = "SELECT appUserEnv FROM AppUserEnv appUserEnv " +
        "WHERE appUserEnv.envVariables.envKeyId = ?1 AND appUserEnv.status = ?2")
    public List<AppUserEnv> findAllByEnvKeyIdAndStatus(Long envKeyId, Long status);

    @Query(value = "SELECT appUserEnv FROM AppUserEnv appUserEnv WHERE appUserEnv.appUser.appUserId = ?1 AND appUserEnv.status = ?2")
    public List<AppUserEnv> findAllByAppUserIdAndStatus(Long appUserId, Long status);

    @Query(value = "SELECT appUserEnv FROM AppUserEnv appUserEnv WHERE appUserEnv.envVariables.envKeyId = ?1 " +
        "AND appUserEnv.appUser.appUserId = ?2 AND appUserEnv.status != ?3")
    public Optional<AppUserEnv> findByEnvKeyIdAndAppUserIdAndStatusNot(Long envKeyId, Long appUserId, Long status);


    @Query(value = "SELECT appUserEnv FROM AppUserEnv appUserEnv WHERE appUserEnv.envVariables.envKey = ?1 " +
            "AND appUserEnv.appUser.appUserId = ?2 AND appUserEnv.status = ?3")
    public Optional<AppUserEnv> findByEnvKeyAndAppUserIdAndStatus(String envKey, Long appUserId, Long status);

}
