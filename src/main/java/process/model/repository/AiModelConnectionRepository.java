package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiModelConnectionRepository extends JpaRepository<AiModelConnection, Long> {

    @Query("select c from AiModelConnection c where c.status <> :status order by c.connectionId desc")
    List<AiModelConnection> findVisibleToPlatformAdmin(@Param("status") Status status);

    @Query("select c from AiModelConnection c where c.status <> :status and c.tenantId = :tenantId order by c.connectionId desc")
    List<AiModelConnection> findVisibleToTenant(@Param("tenantId") Long tenantId, @Param("status") Status status);

    Optional<AiModelConnection> findFirstByTenantIdAndIsDefaultTrueAndStatus(Long tenantId, Status status);

    @Modifying
    @Query("update AiModelConnection c set c.isDefault = false where c.tenantId = :tenantId and c.connectionId <> :keep")
    void clearDefaultForTenantExcept(@Param("tenantId") Long tenantId, @Param("keep") Long keep);
}
