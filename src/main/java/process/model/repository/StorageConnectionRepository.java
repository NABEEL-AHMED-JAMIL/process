package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.StorageConnection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StorageConnectionRepository extends JpaRepository<StorageConnection, Long> {

    List<StorageConnection> findByStatusNotOrderByStorageConnectionIdDesc(Status status);

    Optional<StorageConnection> findByAliasAndStatus(String alias, Status status);

    Optional<StorageConnection> findByAlias(String alias);

    List<StorageConnection> findByTenantIdAndStatus(Long tenantId, Status status);

}
