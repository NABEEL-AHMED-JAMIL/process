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
 * */
@Repository
public interface KafkaConnectionProfileRepository extends JpaRepository<KafkaConnectionProfile, Long> {

    @Query("select p from KafkaConnectionProfile p where p.status <> :status order by p.kafkaConnectionProfileId desc")
    List<KafkaConnectionProfile> findVisibleToPlatformAdmin(@Param("status") Status status);

    /**
     * A tenant's own Kafka profiles, and only those.
     *
     * This used to admit the platform's rows as well, on the reading that a tenant might want to
     * use the platform's broker. That is not what the owner asked for -- a workspace brings its
     * own Kafka, as it brings its own storage -- and it meant every tenant's Kafka screen listed
     * the platform's clusters, their broker addresses and their environment labels.
     *
     * Dispatch is unaffected: KafkaConnectionResolver still falls back to the platform's default
     * profile for a tenant that has none of its own, and it does so on the server without asking
     * what the tenant can see.
     */
    @Query("select p from KafkaConnectionProfile p where p.status <> :status " +
        "and p.tenantId = :tenantId " +
        "order by p.kafkaConnectionProfileId desc")
    List<KafkaConnectionProfile> findVisibleToTenant(@Param("tenantId") Long tenantId, @Param("status") Status status);

    Optional<KafkaConnectionProfile> findByTenantIdAndIsDefaultTrueAndStatus(Long tenantId, Status status);

    long countByTenantIdAndStatusNot(Long tenantId, Status status);

    Optional<KafkaConnectionProfile> findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status status);

    @Transactional
    @Modifying
    @Query("update KafkaConnectionProfile p set p.isDefault = false " +
        "where p.kafkaConnectionProfileId <> :keepDefaultId and p.tenantId is null")
    void clearPlatformDefaultExcept(@Param("keepDefaultId") Long keepDefaultId);

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
