package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, Long> {

    public Optional<SourceTaskType> findSourceTaskTypeBySourceTaskTypeIdAndStatus(Long sourceTaskTypeId, Status status);

    long countByTenantIdAndStatusNot(Long tenantId, Status status);

    public List<SourceTaskType> findByStatus(Status status);

    /**
     * Whether a task type that still exists points at this Kafka connection profile.
     *
     * Deliberately not a plain existsByKafkaConnectionProfileId. A task type is never removed from
     * this table -- deleteSourceTaskType only sets task_type_status = Delete and leaves
     * kafka_connection_profile_id sitting on the row -- so the unqualified check counted task types
     * that no screen lists and nobody can reassign, and the profile they once used could never be
     * deleted again.
     */
    public boolean existsByKafkaConnectionProfileIdAndStatusNot(Long kafkaConnectionProfileId, Status status);

    String FETCH_ALL_SOURCE_TASK_TYPE_SELECT = "select source_task_type.source_task_type_id as sourceTaskTypeId, source_task_type.description as description, \n" +
        "CONCAT(UPPER(SUBSTR(CAST(task_type_status as varchar), 1, 1)), LOWER(SUBSTR(CAST(task_type_status as varchar), 2))) as status,\n" +
        "source_task_type.queue_topic_partition as queueTopicPartition, source_task_type.service_name as serviceName,\n" +
        "source_task_type.kafka_connection_profile_id as kafkaConnectionProfileId,\n" +
        "count(source_task.source_task_type_id) as totalTaskLink\n" +
        "from source_task_type\n" +

        "left join source_task on source_task.source_task_type_id = source_task_type.source_task_type_id\n" +
        "and source_task.task_status != 'Delete'\n";

    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskType();

    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "where source_task_type.tenant_id = :tenantId or source_task_type.tenant_id is null\n" +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskTypeForTenant(@Param("tenantId") Long tenantId);

}
