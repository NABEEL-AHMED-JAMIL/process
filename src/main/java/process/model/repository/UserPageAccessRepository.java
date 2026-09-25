package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.UserPageAccess;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface UserPageAccessRepository extends JpaRepository<UserPageAccess, UserPageAccess.Key> {

    List<UserPageAccess> findByIdAppUserId(Long appUserId);
}
