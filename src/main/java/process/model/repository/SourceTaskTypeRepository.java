package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, Long> {

    @Query(value = "select source_task_type.source_task_type_id as sourceTaskTypeId, source_task_type.description as description,\n" +
        "task_type_status as status, source_task_type.queue_topic_partition as queueTopicPartition, source_task_type.service_name as serviceName,\n" +
        "count(task_detail.source_task_type_id) as totalTaskLink from source_task_type\n" +
        "left join task_detail on task_detail.source_task_type_id = source_task_type.source_task_type_id\n" +
        "group by source_task_type.source_task_type_id order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskType();

    @Query(value = "select count(task_detail.source_task_type_id) as totalTaskLink from source_task_type\n" +
        "left join task_detail on task_detail.source_task_type_id = source_task_type.source_task_type_id\n" +
        "where source_task_type.source_task_type_id = ?1", nativeQuery = true)
    public Long getLinkTaskCountBySourceTaskType(Long sourceTaskTypeId);

}
