package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AppUser;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface AppUserRepository extends CrudRepository<AppUser, Long> {

    public Optional<AppUser> findByUsernameAndStatus(String username, Long status);

    public Optional<AppUser> findByEmailAndStatus(String email, Long status);

    public Boolean existsByUsername(String username);

    public Boolean existsByEmail(String email);

}
