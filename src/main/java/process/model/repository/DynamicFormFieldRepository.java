package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.DynamicFormField;

@Repository
public interface DynamicFormFieldRepository extends JpaRepository<DynamicFormField, Long> {

    @Query(value = "select dynamic_form_id from dynamic_form_field where dynamic_form_field_id = ?1", nativeQuery = true)
    Long findOwningFormId(Long dynamicFormFieldId);

}
