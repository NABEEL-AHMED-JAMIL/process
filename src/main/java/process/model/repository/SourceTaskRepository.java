package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.SourceTask;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskRepository extends CrudRepository<SourceTask, Long> {

    /**
     * Note :- Method use to find the all source task
     * @return List<Long>
     * */
    @Query(value = "select task_detail_id, task_name from source_task\n" +
        "where task_status = 'Active'", nativeQuery = true)
    public List<Long> findAllSourceTask();

    /**
     * Note :- Method use to find the task by task detai id and task status
     * @param taskDetailId
     * @param taskStatus
     * @return Optional<SourceTask>
     * */
    public Optional<SourceTask> findByTaskDetailIdAndTaskStatus(Long taskDetailId, Status taskStatus);

    /**
     * Note :- Method use to find the download source task
     * @return List<SourceTaskProjection>
     * */
    @Query(value = "select st.task_detail_id as taskDetailId, st.task_name as taskName,\n" +
        " st.task_payload  as taskPayload, st.task_status as taskStatus,\n" +
        "stt.queue_topic_partition as queueTopicPartition, stt.service_name as serviceName," +
        "stt.task_type_status as taskTypeStatus, st.pipeline_id as pipelineTaskId, st.home_page_id as homePage\n" +
        "from source_task st\n" +
        "inner join source_task_type stt on stt.source_task_type_id = st.source_task_type_id", nativeQuery = true)
    public List<SourceTaskProjection> downloadListSourceTask();

    /**
     * Note :- Method use to link source task with source task type id
     * @param sourceTaskTypeId
     * @return List<SourceTaskProjection>
     * */
    @Query(value = "select task_detail_id as taskDetailId, task_name as taskName, task_status as taskStatus\n" +
        "from source_task where source_task_type_id = ?1", nativeQuery = true)
    public List<SourceTaskProjection> fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId);

}
