package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AiAgentRepository extends JpaRepository<AiAgent, Long> {

    @Transactional
    @Modifying
    @Query("update AiAgent a set a.tenantId = ?1 where a.tenantId is null")
    int backfillTenantId(Long tenantId);

    public List<AiAgent> findByStatusNotOrderByAiAgentIdDesc(Status status);

    public Optional<AiAgent> findByToolUuid(String toolUuid);

}
