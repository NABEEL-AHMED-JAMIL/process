package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface KafkaConnectionProfileRepository extends JpaRepository<KafkaConnectionProfile, Long> {

    /**
     * Note :- Method use to list every non-deleted profile, unscoped (PLATFORM_ADMIN) -- every
     * profile across every tenant. Kept as a separate query (rather than one with a
     * "(:tenantId is null or ...)" OR) specifically so this call never binds a null Long over
     * JDBC -- pgjdbc has a documented quirk binding an untyped null parameter as `bytea`;
     * Postgres then fails type-checking that placeholder with "operator does not exist: bigint =
     * bytea" even though the "is null" branch would've short-circuited it at runtime (Postgres
     * type-checks every branch of an OR/AND at parse time, not just the one that ends up true --
     * this affects JPQL exactly the same as native queries, confirmed empirically). Not binding
     * the null at all sidesteps the whole issue.
     * @param status
     * @return List<KafkaConnectionProfile>
     * */
    @Query("select p from KafkaConnectionProfile p where p.status <> :status order by p.kafkaConnectionProfileId desc")
    List<KafkaConnectionProfile> findVisibleToPlatformAdmin(@Param("status") Status status);

    /**
     * Note :- Tenant-scoped sibling of the method above -- that tenant's own profiles plus every
     * platform-wide shared one (tenantId is null).
     * @param tenantId
     * @param status
     * @return List<KafkaConnectionProfile>
     * */
    @Query("select p from KafkaConnectionProfile p where p.status <> :status " +
        "and (p.tenantId = :tenantId or p.tenantId is null) " +
        "order by p.kafkaConnectionProfileId desc")
    List<KafkaConnectionProfile> findVisibleToTenant(@Param("tenantId") Long tenantId, @Param("status") Status status);

    /**
     * Note :- Method use to find a specific tenant's own default profile (resolution step 3).
     * @param tenantId
     * @return Optional<KafkaConnectionProfile>
     * */
    Optional<KafkaConnectionProfile> findByTenantIdAndIsDefaultTrueAndStatus(Long tenantId, Status status);

    /**
     * Note :- Method use to find the platform-wide shared default profile (resolution step 4).
     * @return Optional<KafkaConnectionProfile>
     * */
    Optional<KafkaConnectionProfile> findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status status);

    /**
     * Note :- Bulk-clears isDefault on every OTHER platform-wide profile (PLATFORM_ADMIN setting
     * one of its own as default) -- split from clearDefaultForTenantExcept for the same
     * null-parameter-binding reason as findVisibleToPlatformAdmin's javadoc.
     * @param keepDefaultId
     * */
    @Transactional
    @Modifying
    @Query("update KafkaConnectionProfile p set p.isDefault = false " +
        "where p.kafkaConnectionProfileId <> :keepDefaultId and p.tenantId is null")
    void clearPlatformDefaultExcept(@Param("keepDefaultId") Long keepDefaultId);

    /**
     * Note :- Tenant-scoped sibling of the method above -- clears isDefault on every OTHER
     * profile owned by the same tenant, so "at most one default per tenant" holds without a
     * read-modify-write race between concurrent set-as-default calls.
     * @param tenantId
     * @param keepDefaultId
     * */
    @Transactional
    @Modifying
    @Query("update KafkaConnectionProfile p set p.isDefault = false " +
        "where p.kafkaConnectionProfileId <> :keepDefaultId and p.tenantId = :tenantId")
    void clearDefaultForTenantExcept(@Param("tenantId") Long tenantId, @Param("keepDefaultId") Long keepDefaultId);

    @Transactional
    @Modifying
    @Query("update KafkaConnectionProfile p set p.isDefault = false where p.tenantId is null")
    void clearPlatformDefault();

    @Transactional
    @Modifying
    @Query("update KafkaConnectionProfile p set p.isDefault = false where p.tenantId = :tenantId")
    void clearDefaultForTenant(@Param("tenantId") Long tenantId);

}
