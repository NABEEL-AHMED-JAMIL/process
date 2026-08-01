package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.DynamicForm;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface DynamicFormRepository extends JpaRepository<DynamicForm, Long> {

    public Optional<DynamicForm> findDynamicFormByDynamicFormIdAndStatus(Long dynamicFormId, Status status);

    public List<DynamicForm> findByStatusNotOrderByDynamicFormIdDesc(Status status);

}
