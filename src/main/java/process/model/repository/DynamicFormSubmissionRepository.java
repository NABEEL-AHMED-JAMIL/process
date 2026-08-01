package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.DynamicFormSubmission;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface DynamicFormSubmissionRepository extends JpaRepository<DynamicFormSubmission, Long> {

    public List<DynamicFormSubmission> findByDynamicFormIdOrderByDynamicFormSubmissionIdDesc(Long dynamicFormId);

    public Optional<DynamicFormSubmission> findByUuid(String uuid);

}
