package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.RefreshToken;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
@Transactional
public interface RefreshTokenRepository extends CrudRepository<RefreshToken, Long> {

    public Optional<RefreshToken> findByTokenAndStatus(String token, Status status);

    @Modifying
    public int deleteByToken(String token);

}
