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
import java.util.List;
import process.model.projection.AiUsageProjection;

@Repository
public interface AiPromptRunRepository extends JpaRepository<AiPromptRun, Long> {

    Page<AiPromptRun> findAllByPromptIdOrderByRunIdDesc(Long promptId, Pageable pageable);

    Optional<AiPromptRun> findByJobQueueIdAndStepTag(Long jobQueueId, String stepTag);

    List<AiPromptRun> findAllByJobQueueIdOrderByRunIdAsc(Long jobQueueId);

    Optional<AiPromptRun> findFirstByPromptIdOrderByRunIdDesc(Long promptId);

    long countByPromptId(Long promptId);

    /** Tokens the workspace has spent through one connection since a moment -- the daily budget check. */
    @Query("select coalesce(sum(coalesce(r.tokensIn, 0) + coalesce(r.tokensOut, 0)), 0) from AiPromptRun r " +
        "where r.connectionId = :connectionId and r.dateCreated >= :since")
    long tokensSince(@Param("connectionId") Long connectionId, @Param("since") Timestamp since);

    /**
     * Per prompt, between two moments, for the Reports page: calls, failures, tokens, median
     * latency. Tries and pipeline steps both count -- both spend the budget.
     */
    @Query(value = "select r.prompt_id as promptId, coalesce(p.name, 'deleted prompt') as promptName, count(*) as calls, " +
        "count(*) filter (where r.status <> 'ok') as failed, count(*) filter (where r.kind = 'try') as tries, " +
        "coalesce(sum(r.tokens_in), 0) as tokensIn, coalesce(sum(r.tokens_out), 0) as tokensOut, " +
        "coalesce(percentile_cont(0.5) within group (order by r.latency_ms), 0) as medianMs, max(r.date_created) as lastAt " +
        "from ai_prompt_run r left join ai_prompt p on p.prompt_id = r.prompt_id " +
        "where r.date_created >= :from and r.date_created < :to and (:tenantId = 0 or r.tenant_id = :tenantId) " +
        "group by r.prompt_id, p.name order by calls desc", nativeQuery = true)
    List<AiUsageProjection> usageByPrompt(@Param("from") Timestamp from, @Param("to") Timestamp to, @Param("tenantId") long tenantId);

    /** Runs and tokens through one connection since a moment, for the pane's usage line. */
    @Query("select count(r), coalesce(sum(coalesce(r.tokensIn, 0)), 0), coalesce(sum(coalesce(r.tokensOut, 0)), 0) " +
        "from AiPromptRun r where r.connectionId = :connectionId and r.dateCreated >= :since")
    Object[] usageSince(@Param("connectionId") Long connectionId, @Param("since") Timestamp since);
}
