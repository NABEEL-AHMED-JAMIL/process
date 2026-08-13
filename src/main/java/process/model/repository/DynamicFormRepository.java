package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.DynamicForm;
import java.util.List;
import java.util.Optional;

@Repository
public interface DynamicFormRepository extends JpaRepository<DynamicForm, Long> {

    @Transactional
    @Modifying
    @Query("update DynamicForm d set d.tenantId = ?1 where d.tenantId is null")
    int backfillTenantId(Long tenantId);

    public List<DynamicForm> findByStatusNotOrderByDynamicFormIdDesc(Status status);

    public Optional<DynamicForm> findByUuid(String uuid);

}
