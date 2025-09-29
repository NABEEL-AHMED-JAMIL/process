package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.LookupData;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface LookupDataRepository extends CrudRepository<LookupData, Long> {

    /**
     * Note :- Method use to get the LookupData by lookupType if present in db
     * @param lookupType
     * @return LookupData
     * */
    public LookupData findByLookupType(String lookupType);

    /**
     * Note :- Method use to get the LookupData list by lookup id null
     * @return List<LookupData>
     * */
    public List<LookupData> findByParentLookupIdIsNull();
}