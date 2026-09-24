package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.PipelineConfig;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A workspace's configuration values and secrets (MIG-167). Every read names the workspace, or says it crosses them. */
@Repository
public interface PipelineConfigRepository extends CrudRepository<PipelineConfig, Long> {

    List<PipelineConfig> findByTenantIdOrderByConfigKeyAsc(Long tenantId);

    /** Platform admin's view across every workspace. */
    List<PipelineConfig> findAllByOrderByTenantIdAscConfigKeyAsc();

    Optional<PipelineConfig> findByTenantIdAndConfigKey(Long tenantId, String configKey);

    /** The entries of ONE workspace that a run asks for -- the resolver's only read. */
    @Query("select c from PipelineConfig c where c.tenantId = :tenantId and c.configKey in :keys")
    List<PipelineConfig> findForRun(@Param("tenantId") Long tenantId, @Param("keys") Collection<String> keys);
}
