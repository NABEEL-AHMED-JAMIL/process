package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.SourceTask;
import process.model.projection.SourceTaskProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {

    /** Phase 0 migration backfill -- see TenantSeedService. */
    @Transactional
    @Modifying
    @Query("update SourceTask s set s.tenantId = ?1 where s.tenantId is null")
    int backfillTenantId(Long tenantId);

    /**
     * Note :- Method use to find every Active source task, unscoped (PLATFORM_ADMIN) -- used to
     * populate the bulk-upload template's task dropdown. Kept as a separate query (rather than
     * one with a "(:tenantId is null or ...)" OR) specifically so this unscoped call never binds
     * a null Long over JDBC at all -- see findAllSourceTaskForTenant's javadoc for why.
     * @return List<Long>
     * */
    @Query(value = "select task_detail_id from source_task where task_status = 'Active'", nativeQuery = true)
    List<Long> findAllSourceTask();

    /**
     * Note :- Tenant-scoped sibling of the method above. Kept as a second query, rather than one
     * query with a "(:tenantId is null or ...)" OR, specifically so a Platform Admin's call
     * (unscoped, no tenantId at all) never binds a null Long over JDBC in the first place.
     * pgjdbc has a documented quirk binding an untyped null parameter as `bytea`; Postgres then
     * fails type-checking that placeholder against tenant_id (bigint) with "operator does not
     * exist: bigint = bytea" (or "cannot cast type bytea to bigint" if you try to paper over it
     * with an explicit SQL cast) even though the "is null" branch would've short-circuited it at
     * runtime -- Postgres type-checks every branch of an OR at parse time, not just the one that
     * ends up true. Not binding the null at all sidesteps the whole issue.
     * @param tenantId
     * @return List<Long>
     * */
    @Query(value = "select task_detail_id from source_task where task_status = 'Active' and tenant_id = :tenantId", nativeQuery = true)
    List<Long> findAllSourceTaskForTenant(@Param("tenantId") Long tenantId);

    /**
     * Note :- Method use to find the task by task detail id and task status
     * @param taskDetailId
     * @param taskStatus
     * @return Optional<SourceTask>
     * */
    Optional<SourceTask> findByTaskDetailIdAndTaskStatus(Long taskDetailId, Status taskStatus);

    /** Shared select/join/group for downloadListSourceTask()/downloadListSourceTaskForTenant()
     * below -- split into two queries (rather than one with a "(:tenantId is null or ...)" OR)
     * for the same null-parameter-binding reason as findAllSourceTaskForTenant's javadoc. */
    String DOWNLOAD_LIST_SOURCE_TASK_SELECT = "select st.task_detail_id as taskDetailId, st.task_name as taskName,\n" +
        " st.task_payload  as taskPayload, st.task_status as taskStatus,\n" +
        "stt.queue_topic_partition as queueTopicPartition, stt.service_name as serviceName," +
        "stt.task_type_status as taskTypeStatus, st.pipeline_id as pipelineTaskId, st.home_page_id as homePage,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n";

    /**
     * Note :- Method use to download every source task, unscoped (PLATFORM_ADMIN).
     * @return List<SourceTaskProjection>
     * */
    @Query(value = DOWNLOAD_LIST_SOURCE_TASK_SELECT +
        // Deleted tasks are soft-deleted, not gone -- the Task List and every other task
        // listing in the app already excludes them, so the export should too.
        "where st.task_status != 'Delete'", nativeQuery = true)
    public List<SourceTaskProjection> downloadListSourceTask();

    /**
     * Note :- Tenant-scoped sibling of the method above -- without this a tenant's Excel export
     * would include every other tenant's task rows.
     * @param tenantId
     * @return List<SourceTaskProjection>
     * */
    @Query(value = DOWNLOAD_LIST_SOURCE_TASK_SELECT +
        "where st.task_status != 'Delete' and st.tenant_id = :tenantId", nativeQuery = true)
    public List<SourceTaskProjection> downloadListSourceTaskForTenant(@Param("tenantId") Long tenantId);

    /**
     * Note :- Method use to link source task with source task type id -- powers the Link Source
     * Task picker (Settings > Source Task Type > Link Source Task count), so a user can browse
     * every task built on a given type and pick one, grouped by the task's TASK_GROUPS lookup
     * (groupLabel) the same way the Task List's own Group column reads it. tenantId null
     * (PLATFORM_ADMIN) means unscoped; a real tenantId restricts to that tenant's own tasks --
     * native query, bypasses Hibernate's @Filter.
     * @param sourceTaskTypeId
     * @param tenantId
     * @return List<SourceTaskProjection>
     * */
    @Query(value = "select st.task_detail_id as taskDetailId, st.task_name as taskName, st.task_status as taskStatus,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n" +
        // Deleted tasks stay in the table (soft delete) but shouldn't show up as a pickable
        // target here -- same as the Task List, which already filters them out.
        "where st.source_task_type_id = :sourceTaskTypeId and st.task_status != 'Delete'", nativeQuery = true)
    public List<SourceTaskProjection> fetchAllLinkSourceTaskWithSourceTaskTypeId(
        @Param("sourceTaskTypeId") Long sourceTaskTypeId);

    /**
     * Tenant-scoped sibling of the method above -- kept as a second query, rather than one query
     * with a "(:tenantId is null or ...)" OR, specifically so a Platform Admin's call (unscoped,
     * no tenantId at all) never binds a null Long over JDBC in the first place. pgjdbc has a
     * documented quirk binding an untyped null parameter as `bytea`; Postgres then fails
     * type-checking that placeholder against st.tenant_id (bigint) with "operator does not
     * exist: bigint = bytea" (or "cannot cast type bytea to bigint" if you try to paper over it
     * with an explicit SQL cast) even though the "is null" branch would've short-circuited it at
     * runtime -- Postgres type-checks every branch of an OR at parse time, not just the one
     * that ends up true. Not binding the null at all sidesteps the whole issue.
     * @param sourceTaskTypeId
     * @param tenantId
     * @return List<SourceTaskProjection>
     * */
    @Query(value = "select st.task_detail_id as taskDetailId, st.task_name as taskName, st.task_status as taskStatus,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n" +
        "where st.source_task_type_id = :sourceTaskTypeId and st.task_status != 'Delete' " +
        "and st.tenant_id = :tenantId", nativeQuery = true)
    public List<SourceTaskProjection> fetchAllLinkSourceTaskWithSourceTaskTypeIdForTenant(
        @Param("sourceTaskTypeId") Long sourceTaskTypeId, @Param("tenantId") Long tenantId);

}
