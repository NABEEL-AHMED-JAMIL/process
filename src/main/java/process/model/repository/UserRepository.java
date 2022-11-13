package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.User;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface UserRepository extends CrudRepository<User, Long> {
}
