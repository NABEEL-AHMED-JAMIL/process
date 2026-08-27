package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.TaskForm;

import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface TaskFormRepository extends JpaRepository<TaskForm, Long> {

    List<TaskForm> findAllByFormStatusNot(Status status);

    /**
     * The form a pipeline should use for this caller.
     *
     * A tenant's own definition wins over the shared one, so a tenant can tailor a pipeline's
     * form without affecting anybody else. Ordered so the tenant row sorts first and the
     * caller can simply take the head.
     */
    @Query("select f from TaskForm f where f.pipelineId = ?1 and f.formStatus <> 'Delete' "
        + "and (f.tenantId = ?2 or f.tenantId is null) "
        + "order by case when f.tenantId is null then 1 else 0 end")
    List<TaskForm> findForPipeline(String pipelineId, Long tenantId);

    Optional<TaskForm> findByTaskFormIdAndFormStatusNot(Long taskFormId, Status status);
}
