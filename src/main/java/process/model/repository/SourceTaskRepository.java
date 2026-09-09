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
 * */
@Repository
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {

    long countByTenantIdAndTaskStatusNot(Long tenantId, Status status);

    /**
     * How many DIFFERENT pipelines a workspace runs, as against how many tasks it has.
     *
     * The two are not the same and neither implies the other: the demo workspace has 19 tasks
     * across 15 pipelines, because four pipelines carry two tasks each. Nothing else on the
     * tenants screen distinguishes "nineteen tasks all doing one thing" from "nineteen tasks
     * doing fifteen different things" -- source_task_type is a single service row and stays at
     * 1 whatever the tasks underneath it are.
     *
     * Blank ids are excluded rather than counted as a pipeline of their own; a task with no
     * pipeline is unconfigured, not a sixteenth kind of work.
     */
    @Query("select count(distinct st.pipelineId) from SourceTask st "
        + "where st.tenantId = ?1 and st.taskStatus <> ?2 "
        + "and st.pipelineId is not null and st.pipelineId <> ''")
    long countDistinctPipelinesByTenantId(Long tenantId, Status status);

    @Transactional
    @Modifying
    @Query("update SourceTask s set s.tenantId = ?1 where s.tenantId is null")
    int backfillTenantId(Long tenantId);

    @Query(value = "select task_detail_id from source_task where task_status = 'Active'", nativeQuery = true)
    List<Long> findAllSourceTask();

    @Query(value = "select task_detail_id from source_task where task_status = 'Active' and tenant_id = :tenantId", nativeQuery = true)
    List<Long> findAllSourceTaskForTenant(@Param("tenantId") Long tenantId);

    Optional<SourceTask> findByTaskDetailIdAndTaskStatus(Long taskDetailId, Status taskStatus);

    String DOWNLOAD_LIST_SOURCE_TASK_SELECT = "select st.task_detail_id as taskDetailId, st.task_name as taskName,\n" +
        " st.task_payload  as taskPayload, st.task_status as taskStatus,\n" +
        "stt.queue_topic_partition as queueTopicPartition, stt.service_name as serviceName," +
        "stt.task_type_status as taskTypeStatus, st.pipeline_id as pipelineTaskId, st.home_page_id as homePage,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n";

    @Query(value = DOWNLOAD_LIST_SOURCE_TASK_SELECT +

        "where st.task_status != 'Delete'", nativeQuery = true)
    public List<SourceTaskProjection> downloadListSourceTask();

    @Query(value = DOWNLOAD_LIST_SOURCE_TASK_SELECT +
        "where st.task_status != 'Delete' and st.tenant_id = :tenantId", nativeQuery = true)
    public List<SourceTaskProjection> downloadListSourceTaskForTenant(@Param("tenantId") Long tenantId);

    @Query(value = "select st.task_detail_id as taskDetailId, st.task_name as taskName, st.task_status as taskStatus,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n" +

        "where st.source_task_type_id = :sourceTaskTypeId and st.task_status != 'Delete'", nativeQuery = true)
    public List<SourceTaskProjection> fetchAllLinkSourceTaskWithSourceTaskTypeId(
        @Param("sourceTaskTypeId") Long sourceTaskTypeId);

    @Query(value = "select st.task_detail_id as taskDetailId, st.task_name as taskName, st.task_status as taskStatus,\n" +
        "ldg.lookup_type as groupLabel\n" +
        "from source_task st\n" +
        "left join lookup_data ldg on cast(ldg.lookup_id as varchar(10)) = st.group_id\n" +
        "where st.source_task_type_id = :sourceTaskTypeId and st.task_status != 'Delete' " +
        "and st.tenant_id = :tenantId", nativeQuery = true)
    public List<SourceTaskProjection> fetchAllLinkSourceTaskWithSourceTaskTypeIdForTenant(
        @Param("sourceTaskTypeId") Long sourceTaskTypeId, @Param("tenantId") Long tenantId);

}
