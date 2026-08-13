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

@Repository
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {

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
