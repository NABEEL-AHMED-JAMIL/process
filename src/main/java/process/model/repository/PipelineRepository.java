package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.projection.PipelineRowProjection;
import process.model.projection.PipelineSummaryProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    String ROW_SELECT = "select p.pipeline_key as pipelineKey, p.pipeline_id as pipelineId, p.pipeline_name as pipelineName,\n" +
        "p.description as description, p.tenant_id as tenantId, p.source_task_type_id as sourceTaskTypeId,\n" +
        "cast(p.status as varchar) as status, p.date_created as dateCreated, p.created_by as createdBy, p.updated_by as updatedBy,\n" +
        "(select count(*) from pipeline_field f where f.pipeline_key = p.pipeline_key) as fieldCount,\n" +
        "(select count(*) from pipeline_field f where f.pipeline_key = p.pipeline_key and f.required) as requiredCount\n" +
        "from pipeline p left join source_task_type t on t.source_task_type_id = p.source_task_type_id\n";

    /**
     * The filters a list page carries, each as a value that means "any" when absent (0, '',
     * false) rather than null -- Postgres cannot type a null parameter in a native query. A
     * search term arrives already lower-cased and wrapped in % so the query stays one line.
     */
    String ROW_WHERE = "where cast(p.status as varchar) <> 'Delete'\n" +
        "and (:tenantId = 0 or p.tenant_id = :tenantId)\n" +
        "and (:topicId = 0 or p.source_task_type_id = :topicId)\n" +
        "and (:untopped = false or p.source_task_type_id is null)\n" +
        "and (:status = '' or cast(p.status as varchar) = :status)\n" +
        "and (:createdBy = 0 or p.created_by = :createdBy)\n" +
        "and (:q = '' or lower(p.pipeline_name) like :q or lower(p.pipeline_id) like :q\n" +
        "     or lower(coalesce(p.description, '')) like :q or lower(coalesce(t.service_name, '')) like :q\n" +
        "     or lower(coalesce(t.queue_topic_partition, '')) like :q)\n";

    /** One page of rows under the filters; counts stand in for the fields. Newest first. */
    @Query(value = ROW_SELECT + ROW_WHERE + "order by p.pipeline_key desc",
        countQuery = "select count(*) from pipeline p left join source_task_type t on t.source_task_type_id = p.source_task_type_id\n" + ROW_WHERE,
        nativeQuery = true)
    org.springframework.data.domain.Page<PipelineRowProjection> pageRows(
        @Param("tenantId") long tenantId, @Param("topicId") long topicId, @Param("untopped") boolean untopped,
        @Param("status") String status, @Param("createdBy") long createdBy, @Param("q") String q,
        org.springframework.data.domain.Pageable pageable);

    /** The whole scope in five numbers, for the tiles above the list; unfiltered on purpose. */
    @Query(value = "select count(*) as total, count(*) filter (where cast(p.status as varchar) = 'Active') as active,\n" +
        "count(distinct p.source_task_type_id) as topics, count(*) filter (where p.source_task_type_id is null) as untopped,\n" +
        "(select count(*) from pipeline_field f join pipeline q on q.pipeline_key = f.pipeline_key\n" +
        "  where cast(q.status as varchar) <> 'Delete' and (:tenantId = 0 or q.tenant_id = :tenantId)) as fields\n" +
        "from pipeline p where cast(p.status as varchar) <> 'Delete' and (:tenantId = 0 or p.tenant_id = :tenantId)", nativeQuery = true)
    PipelineSummaryProjection summarise(@Param("tenantId") long tenantId);

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
