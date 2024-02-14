package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.LookupData;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    public Optional<LookupData> findByLookupType(String lookupType);

    public List<LookupData> findByParentLookupIsNull();

    @Query(value = "select ld.*, au.app_user_id, au.username from lookup_data ld\n" +
        "join app_users au on au.app_user_id  = ld.app_user_id  \n" +
        "where au.username = ?1 and ld.parent_lookup_id  is null", nativeQuery = true)
    public List<LookupData> fetchAllLookup(String username);

    @Query(value = "select ld.*, au.app_user_id, au.username from lookup_data ld\n" +
        "join app_users au on au.app_user_id  = ld.app_user_id  \n" +
        "where ld.lookup_id = ?1 and au.username = ?2 ", nativeQuery = true)
    public Optional<LookupData> findByParentLookupAndAppUserUsername(Long parentLookupId, String username);

}