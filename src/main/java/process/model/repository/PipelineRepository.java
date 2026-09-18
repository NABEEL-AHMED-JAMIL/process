package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    /** Everything a platform admin sees -- every tenant's forms, in one list. */
    public List<Pipeline> findAllByStatusNot(Status status);

    /**
     * A tenant's own form definitions, and only those.
     *
     * This used to also admit a null-tenant row as a "shared, every-tenant-sees-it" definition
     * (the same reading Kafka Connections walked back from -- see
     * KafkaConnectionProfileRepository.findVisibleToTenant) -- a workspace's task-payload schema
     * is exactly the kind of thing that should not leak from one tenant's admin screen into
     * another's, the same way a storage or Kafka connection would not.
     *
     * A derived query, not a hand-written @Query string: the two sibling lookups above and below
     * already express "not deleted" as a typed Status parameter rather than the JPQL literal
     * 'Delete', so a rename of that enum constant is caught by the compiler here too instead of
     * silently drifting out of sync in a string only this method used to carry.
     */
    public List<Pipeline> findAllByTenantIdAndStatusNotOrderByPipelineKeyDesc(Long tenantId, Status status);

    /**
     * The form a pipeline should use for this tenant, if it has defined one.
     *
     * Strict: a tenant with no form of its own for this pipeline gets none back, and the task
     * screen falls back to plain tags (see PipelineServiceImpl.formForPipeline) -- the same
     * "most pipelines have no form" case that already exists today, not a shared definition
     * borrowed from another tenant or a platform-wide default.
     */
    public List<Pipeline> findAllByPipelineIdAndTenantIdAndStatusNot(String pipelineId, Long tenantId, Status status);

    public Optional<Pipeline> findByPipelineKeyAndStatusNot(Long pipelineKey, Status status);

    /** The pipelines that publish on one topic -- what the task screen offers once a topic is picked. */
    public List<Pipeline> findAllBySourceTaskTypeIdAndStatusNotOrderByPipelineNameAsc(Long sourceTaskTypeId, Status status);

    /** The pipelines of many topics at once, for a pane that lists a profile's topics. */
    public List<Pipeline> findAllBySourceTaskTypeIdInAndStatusNotOrderByPipelineNameAsc(java.util.Collection<Long> sourceTaskTypeIds, Status status);

    /** How many pipelines still name a topic; a topic with any cannot be deleted. */
    public long countBySourceTaskTypeIdAndStatusNot(Long sourceTaskTypeId, Status status);
}
