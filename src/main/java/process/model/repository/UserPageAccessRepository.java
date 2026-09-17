package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.UserPageAccess;
import java.util.Collection;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface UserPageAccessRepository extends JpaRepository<UserPageAccess, UserPageAccess.Key> {

    List<UserPageAccess> findByIdAppUserId(Long appUserId);

    /** Everyone's exceptions in one read -- what the grid needs. */
    List<UserPageAccess> findByIdAppUserIdIn(Collection<Long> appUserIds);

    @Modifying
    @Query("delete from UserPageAccess u where u.id.appUserId = :appUserId and u.id.pageKey = :pageKey")
    int deleteOne(Long appUserId, String pageKey);

    @Modifying
    @Query("delete from UserPageAccess u where u.id.appUserId = :appUserId")
    int deleteAllFor(Long appUserId);
}
