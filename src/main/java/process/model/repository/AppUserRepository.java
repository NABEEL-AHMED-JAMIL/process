package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface AppUserRepository extends CrudRepository<AppUser, Long> {

    public Optional<AppUser> findByUsernameAndStatus(String username, Status status);

    public Optional<AppUser> findByEmailAndStatus(String email, Status status);

    public Boolean existsByUsername(String username);

    public Boolean existsByEmail(String email);

}
