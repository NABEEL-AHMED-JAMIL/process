package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.TaskDetail;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface TaskDetailRepository extends CrudRepository<TaskDetail, Long> {

    @Query(value = "select task_detail_id from task_detail", nativeQuery = true)
    public List<Long> findAllTaskDetail();

    public Optional<TaskDetail> findByTaskDetailIdAndTaskStatus(Long taskDetailId, Status taskStatus);
}
