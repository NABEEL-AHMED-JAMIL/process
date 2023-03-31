package process.service.imp;

import com.google.gson.Gson;
import org.hibernate.query.internal.NativeQueryImpl;
import org.hibernate.transform.AliasToEntityMapResultTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.payload.response.QueryResponse;
import javax.persistence.*;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;


/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class QueryService {

    private Logger logger = LoggerFactory.getLogger(QueryService.class);

    @PersistenceContext
    private EntityManager _em;

    public Object executeQueryForSingleResult(String queryStr) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getSingleResult();
    }

    public List<Object[]> executeQuery(String queryStr) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        return query.getResultList();
    }

    public List<Object[]> executeQuery(String queryStr, Pageable paging) {
        logger.info("Execute Query :- " + queryStr);
        Query query = this._em.createNativeQuery(queryStr);
        if (paging != null) {
            query.setFirstResult(paging.getPageNumber() * paging.getPageSize());
            query.setMaxResults(paging.getPageSize());
        }
        return query.getResultList();
    }

    public QueryResponse executeQueryResponse(String queryString) {
        logger.info("Execute Query :- " + queryString);
        Query query = this._em.createNativeQuery(queryString);
        NativeQueryImpl nativeQuery = (NativeQueryImpl) query;
        nativeQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);
        List<Map<String,Object>> result = nativeQuery.getResultList();
        QueryResponse itemResponse=new QueryResponse();
        if (result != null && result.size() > 0) {
            itemResponse.setQuery(queryString);
            itemResponse.setData(result);
            itemResponse.setColumn(result.get(0).keySet());
        }
        return itemResponse;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
