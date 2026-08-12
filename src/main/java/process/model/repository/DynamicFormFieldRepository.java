package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.DynamicFormField;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface DynamicFormFieldRepository extends JpaRepository<DynamicFormField, Long> {

    /**
     * Method use to resolve the owning DynamicForm's id for a field -- the OneToMany on
     * DynamicForm.fields is unidirectional (no back-reference on DynamicFormField itself), so
     * this is the only way to find which form (and therefore which tenant) a field belongs to,
     * needed by updateField/deleteField's IDOR ownership check.
     * @param dynamicFormFieldId
     * @return Long
     * */
    @Query(value = "select dynamic_form_id from dynamic_form_field where dynamic_form_field_id = ?1", nativeQuery = true)
    Long findOwningFormId(Long dynamicFormFieldId);

}
