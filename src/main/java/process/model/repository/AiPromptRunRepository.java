package process.model.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.AiPromptRun;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public interface AiPromptRunRepository extends JpaRepository<AiPromptRun, Long> {

    Page<AiPromptRun> findAllByPromptIdOrderByRunIdDesc(Long promptId, Pageable pageable);

    Optional<AiPromptRun> findByJobQueueIdAndStepTag(Long jobQueueId, String stepTag);

    Optional<AiPromptRun> findFirstByPromptIdOrderByRunIdDesc(Long promptId);

    long countByPromptId(Long promptId);

    /** Tokens the workspace has spent through one connection since a moment -- the daily budget check. */
    @Query("select coalesce(sum(coalesce(r.tokensIn, 0) + coalesce(r.tokensOut, 0)), 0) from AiPromptRun r " +
        "where r.connectionId = :connectionId and r.dateCreated >= :since")
    long tokensSince(@Param("connectionId") Long connectionId, @Param("since") Timestamp since);

    /** Runs and tokens through one connection since a moment, for the pane's usage line. */
    @Query("select count(r), coalesce(sum(coalesce(r.tokensIn, 0)), 0), coalesce(sum(coalesce(r.tokensOut, 0)), 0) " +
        "from AiPromptRun r where r.connectionId = :connectionId and r.dateCreated >= :since")
    Object[] usageSince(@Param("connectionId") Long connectionId, @Param("since") Timestamp since);
}
