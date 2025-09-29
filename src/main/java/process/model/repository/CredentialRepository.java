package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Credential;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface CredentialRepository extends CrudRepository<Credential, Long> {

    @Query(value = "SELECT credential FROM Credential credential WHERE credential.credentialId = ?1 " +
        "AND credential.appUser.username = ?2 AND credential.status = ?3")
    public Optional<Credential> findByCredentialIdAndUsernameAndStatus(Long credentialId, String username, Long status);

    @Query(value = "SELECT credential FROM Credential credential WHERE credential.credentialId = ?1 " +
        "AND credential.appUser.username = ?2 AND credential.status != ?3")
    public Optional<Credential> findByCredentialIdAndUsernameAndStatusNotIn(Long credentialId, String username, Long status);

    @Query(value = "SELECT credential FROM Credential credential WHERE " +
        "credential.appUser.username = ?1 AND credential.status != ?2")
    public List<Credential> findAllByUsernameAndStatusNotIn(String username, Long status);

}
