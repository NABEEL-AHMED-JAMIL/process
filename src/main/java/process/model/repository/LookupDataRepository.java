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
 */
@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    /**
     * Note :- Method use to get the LookupData by lookupType if present in db
     * @param lookupType
     * @return LookupData
     * */
    public LookupData findByLookupType(String lookupType);

    /**
     * Note :- Method use to get the LookupData list by lookup id null
     * @return List<LookupData>
     * */
    public List<LookupData> findByParentLookupIdIsNull();

    /**
     * Backfills tenantId on pre-existing BUCKET_LIST children (the only lookups that represent
     * an actual tenant-owned resource, see LookupData's own javadoc) to the Default tenant, same
     * pattern/purpose as SourceJobRepository.backfillTenantId -- run once via TenantSeedService
     * so buckets that predate per-tenant bucket scoping keep working for the tenant that
     * actually owns them instead of becoming invisible to everyone but PLATFORM_ADMIN.
     * @param tenantId
     * @return int
     * */
    @Transactional
    @Modifying
    @Query("update LookupData l set l.tenantId = ?1 where l.tenantId is null and l.parent.lookupId in "
        + "(select p.lookupId from LookupData p where p.lookupType = 'BUCKET_LIST')")
    int backfillBucketTenantId(Long tenantId);
}