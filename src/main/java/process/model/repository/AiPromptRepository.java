package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AiPrompt;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiPromptRepository extends JpaRepository<AiPrompt, Long> {

    @Query("select p from AiPrompt p where p.status <> :status order by p.promptId desc")
    List<AiPrompt> findVisibleToPlatformAdmin(@Param("status") Status status);

    @Query("select p from AiPrompt p where p.status <> :status and p.tenantId = :tenantId order by p.promptId desc")
    List<AiPrompt> findVisibleToTenant(@Param("tenantId") Long tenantId, @Param("status") Status status);

    Optional<AiPrompt> findByPromptUuid(String promptUuid);

    long countByConnectionIdAndStatusNot(Long connectionId, Status status);

    /** Prompts that run on the workspace default, whichever connection that is. */
    long countByTenantIdAndConnectionIdIsNullAndStatusNot(Long tenantId, Status status);
}
