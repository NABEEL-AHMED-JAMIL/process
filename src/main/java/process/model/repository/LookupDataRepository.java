package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.LookupData;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface LookupDataRepository extends JpaRepository<LookupData, Long> {

    public LookupData findByLookupType(String lookupType);
}
