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

    public Optional<StorageConnection> findByAliasAndStatus(String alias, Status status);

    public Optional<StorageConnection> findByAlias(String alias);

    public List<StorageConnection> findByTenantIdAndStatus(Long tenantId, Status status);

    public long countByTenantIdAndStatusNot(Long tenantId, Status status);

}
