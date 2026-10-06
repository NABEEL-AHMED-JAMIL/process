package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.TaskReference;

import java.util.List;
import java.util.Optional;

/** Home pages and task groups (MIG-167). Every read names the workspace, or says it crosses them. */
@Repository
public interface TaskReferenceRepository extends CrudRepository<TaskReference, Long> {

    List<TaskReference> findByTenantIdAndKindOrderByNameAsc(Long tenantId, String kind);

    /** Platform admin's view across every workspace. */
    List<TaskReference> findByKindOrderByTenantIdAscNameAsc(String kind);

    Optional<TaskReference> findByTenantIdAndKindAndName(Long tenantId, String kind, String name);

    /** A home page's URL for the dispatcher, by the id a task holds. */
    @Query("select r.value from TaskReference r where r.id = :id and r.kind = 'HOME_PAGE'")
    Optional<String> findHomePageUrl(@Param("id") Long id);
}
