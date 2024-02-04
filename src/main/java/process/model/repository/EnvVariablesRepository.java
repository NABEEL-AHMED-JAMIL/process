package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.EnvVariables;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface EnvVariablesRepository extends CrudRepository<EnvVariables, Long> {

    public Optional<EnvVariables> findByEnvKeyIdAndStatusNot(Long envKeyId, Long status);

    public Optional<EnvVariables> findByEnvKeyAndStatusNot(String envKey, Long status);

    public List<EnvVariables> findAllByStatusNot(Long status);

}
