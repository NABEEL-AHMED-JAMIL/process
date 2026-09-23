package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.StorageConnection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface StorageConnectionRepository extends JpaRepository<StorageConnection, Long> {

    public List<StorageConnection> findByStatusNotOrderByStorageConnectionIdDesc(Status status);

    // No lookup by alias alone (MIG-53): an alias is unique within a workspace, so every lookup says
    // whose alias it means. StorageConnectionLookup is where the rules for choosing live.

    public Optional<StorageConnection> findByTenantIdAndAlias(Long tenantId, String alias);

    public Optional<StorageConnection> findByTenantIdIsNullAndAlias(String alias);

    public List<StorageConnection> findAllByAlias(String alias);

    public List<StorageConnection> findByTenantIdAndStatus(Long tenantId, Status status);

    public long countByTenantIdAndStatusNot(Long tenantId, Status status);

}
