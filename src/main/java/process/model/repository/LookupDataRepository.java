package process.model.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.LookupData;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    /**
     * Method use to get the LookupData by lookupType if present in db
     * @param lookupType
     * @return LookupData
     * */
    public LookupData findByLookupType(String lookupType);
}