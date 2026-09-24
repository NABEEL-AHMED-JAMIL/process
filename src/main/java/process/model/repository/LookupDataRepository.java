package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.LookupData;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    public LookupData findByLookupType(String lookupType);

    public List<LookupData> findByParentLookupIdIsNull();

    /**
     * The top-level lookups with their children fetched in the same read (MIG-67). The cache rebuild runs
     * from @PostConstruct, outside any transaction, and walked the lazy children of what
     * findByParentLookupIdIsNull returned -- which only worked while enable_lazy_load_no_trans did.
     */
    @Query("select distinct l from LookupData l left join fetch l.children where l.parent is null")
    List<LookupData> findRootsWithChildren();

    /** One family's rows, by query rather than through the parent's lazy collection (MIG-67). */
    @Query("select l from LookupData l where l.parent.lookupId = ?1 order by l.lookupId")
    List<LookupData> findChildrenOf(Long parentLookupId);

    long countByTenantIdAndParent_LookupType(Long tenantId, String lookupType);

    @Transactional
    @Modifying
    @Query("update LookupData l set l.tenantId = ?1 where l.tenantId is null and l.parent.lookupId in "
        + "(select p.lookupId from LookupData p where p.lookupType = 'BUCKET_LIST')")
    int backfillBucketTenantId(Long tenantId);
}