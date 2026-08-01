package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.DynamicFormField;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface DynamicFormFieldRepository extends JpaRepository<DynamicFormField, Long> {

    public Optional<DynamicFormField> findDynamicFormFieldByDynamicFormFieldId(Long dynamicFormFieldId);

}
