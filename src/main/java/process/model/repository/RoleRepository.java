package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Role;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface RoleRepository extends CrudRepository<Role, Long> {
}
