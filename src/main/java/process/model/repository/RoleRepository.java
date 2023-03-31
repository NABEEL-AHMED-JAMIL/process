package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.Role;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface RoleRepository extends CrudRepository<Role, Long> {

    public Optional<Role> findByRoleNameAndStatus(String roleName, Status status);
}
