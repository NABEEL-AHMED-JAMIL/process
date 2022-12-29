package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, Long> {

    public Optional<SourceTaskType> findSourceTaskTypeBySourceTaskTypeIdAndStatus(Long sourceTaskTypeId, Status status);

    @Query(value = "select source_task_type.source_task_type_id as sourceTaskTypeId, source_task_type.description as description, task_type_status as status,\n" +
        "source_task_type.queue_topic_partition as queueTopicPartition, source_task_type.service_name as serviceName,\n" +
        "count(source_task.source_task_type_id) as totalTaskLink, source_task_type.is_schema_register as schemaRegister, source_task_type.schema_payload as schemaPayload\n" +
        "from source_task_type\n" +
        "left join source_task on source_task.source_task_type_id = source_task_type.source_task_type_id\n" +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskType();

}
