package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.LookupData;
import java.util.List;

@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    public LookupData findByLookupType(String lookupType);

    public List<LookupData> findByParentLookupIdIsNull();

    long countByTenantIdAndParent_LookupType(Long tenantId, String lookupType);

    @Transactional
    @Modifying
    @Query("update LookupData l set l.tenantId = ?1 where l.tenantId is null and l.parent.lookupId in "
        + "(select p.lookupId from LookupData p where p.lookupType = 'BUCKET_LIST')")
    int backfillBucketTenantId(Long tenantId);
}